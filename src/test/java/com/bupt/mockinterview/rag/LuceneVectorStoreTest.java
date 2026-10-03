package com.bupt.mockinterview.rag;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R4 批 3：{@link LuceneVectorStore} 核心保证（POJO + @TempDir，直接 new，不起 Spring 上下文）。
 *
 * <p>对应实施计划 v2 复审 Gate：
 * <ul>
 *   <li>a. AND 删除：删一个 source，该 source 归零、同用户另一 source 与<b>另一用户同 id source</b>都不动；</li>
 *   <li>b. pre-filter 召回：u1 仅 1 个目标块（cos 0.8），u2 灌 60 个全局更近块（cos≥0.85），
 *       u1 search 仍必中自己的目标——post-filter 小候选窗会被 u2 60 块占满而漏掉，能中即证明是 pre-filter；</li>
 *   <li>c. 持久化：close 后同目录重开，数据仍在；</li>
 *   <li>d. rollback：beginReplace 删旧+加新后回滚，检索回到旧块，且回滚后 writer 可继续写；</li>
 *   <li>e. 维度 pad：512 维向量写入 1024 维索引不炸，归一化后排序正确。</li>
 * </ul>
 */
class LuceneVectorStoreTest {

    @TempDir
    Path tempDir;

    private static final int D = 16;

    // ---------- a. AND 删除 ----------

    @Test
    void deleteBySource_removesOnlyTargetSource_andKeepsOtherSourceAndOtherUser() throws Exception {
        try (LuceneVectorStore s = new LuceneVectorStore(tempDir.resolve("and"), D)) {
            // u1：source note#10 两块、note#20 三块
            s.add(chunk(1L, "note", "10", 0, "u1-n10-a", vec(1, 0)));
            s.add(chunk(1L, "note", "10", 1, "u1-n10-b", vec(1, 0.001)));
            s.add(chunk(1L, "note", "20", 0, "u1-n20-a", vec(0, 1)));
            s.add(chunk(1L, "note", "20", 1, "u1-n20-b", vec(0, -1)));
            s.add(chunk(1L, "note", "20", 2, "u1-n20-c", vec(0.5, 0.5)));
            // u2：同样叫 note#10（AND 语义必须保住它；Term 变长参 OR 会误删）
            s.add(chunk(2L, "note", "10", 0, "u2-n10", vec(0.7, 0.7)));
            s.commitBatch();

            s.deleteBySource(1L, "note", "10");

            // u1 只剩 note#20 的三块，note#10 归零
            List<KnowledgeChunk.ScoredChunk> u1 = s.search(1L, vec(1, 1), 10);
            assertThat(u1).hasSize(3);
            assertThat(u1).allSatisfy(sc -> assertThat(sc.chunk().sourceId()).isEqualTo("20"));
            assertThat(contentOf(u1)).doesNotContain("u1-n10-a", "u1-n10-b");

            // u2 的 note#10 不受影响（userId MUST 子句生效）
            List<KnowledgeChunk.ScoredChunk> u2 = s.search(2L, vec(0.7, 0.7), 5);
            assertThat(u2).hasSize(1);
            assertThat(u2.get(0).chunk().content()).isEqualTo("u2-n10");
        }
    }

    // ---------- b. pre-filter 召回（极端 post-filter 场景） ----------

    @Test
    void preFilter_userWithSingleDoc_recallsIt_evenWhenOtherUserFillsGlobalTop() throws Exception {
        try (LuceneVectorStore s = new LuceneVectorStore(tempDir.resolve("filter"), D)) {
            // u1 唯一目标块：与查询 q=e0 的余弦 0.8
            s.add(chunk(1L, "note", "1", 0, "TARGET_U1", vec(0.8, 0.6)));
            // u2 60 个全局更近块：余弦 0.85..0.999，全部比 u1 目标更靠近 q
            for (int i = 0; i < 60; i++) {
                double a = 0.85 + 0.149 * (i / 59.0);          // 0.85 → 0.999
                double sin = Math.sqrt(1.0 - a * a);
                double y = (i % 2 == 0) ? sin : -sin;
                s.add(chunk(2L, "note", String.valueOf(100 + i), 0, "u2-" + i, vec(a, y)));
            }
            s.commitBatch();

            List<KnowledgeChunk.ScoredChunk> u1 = s.search(1L, vec(1, 0), 3);
            assertThat(u1).isNotEmpty();                                  // 资料极少也必须召回自己
            assertThat(u1).allSatisfy(sc -> assertThat(sc.chunk().userId()).isEqualTo(1L));
            assertThat(contentOf(u1)).contains("TARGET_U1");             // 且命中的正是目标块

            List<KnowledgeChunk.ScoredChunk> u2 = s.search(2L, vec(1, 0), 3);
            assertThat(u2).isNotEmpty();
            assertThat(u2).allSatisfy(sc -> assertThat(sc.chunk().userId()).isEqualTo(2L));
            assertThat(contentOf(u2)).doesNotContain("TARGET_U1");
        }
    }

    // ---------- c. 持久化 ----------

    @Test
    void closeAndReopen_sameDirectory_dataPersists() throws Exception {
        Path dir = tempDir.resolve("persist");
        try (LuceneVectorStore s = new LuceneVectorStore(dir, D)) {
            s.add(chunk(1L, "note", "1", 0, "PERSISTED", vec(1, 0)));
            s.commitBatch();
        }
        try (LuceneVectorStore reopened = new LuceneVectorStore(dir, D)) {
            List<KnowledgeChunk.ScoredChunk> hits = reopened.search(1L, vec(1, 0), 5);
            assertThat(contentOf(hits)).contains("PERSISTED");
            assertThat(reopened.size()).isGreaterThanOrEqualTo(1);
        }
    }

    // ---------- d. rollback ----------

    @Test
    void rollback_restoresOldChunks_andWriterRemainsUsable() throws Exception {
        try (LuceneVectorStore s = new LuceneVectorStore(tempDir.resolve("rollback"), D)) {
            s.add(chunk(1L, "note", "1", 0, "OLD", vec(1, 0)));
            s.commitBatch();

            s.beginReplace(1L, "note", "1");
            s.add(chunk(1L, "note", "1", 0, "NEW", vec(0, 1)));  // 未提交的删旧+加新
            s.rollbackBatch();

            // 回到旧已提交快照：OLD 在、NEW 不在
            List<KnowledgeChunk.ScoredChunk> oldView = s.search(1L, vec(1, 0), 5);
            assertThat(contentOf(oldView)).contains("OLD");
            assertThat(contentOf(oldView)).doesNotContain("NEW");

            // rollback 已重建 writer，后续写入正常提交可见
            s.add(chunk(1L, "note", "2", 0, "AFTER", vec(0, 1)));
            s.commitBatch();
            assertThat(contentOf(s.search(1L, vec(0, 1), 5))).contains("AFTER");
        }
    }

    // ---------- e. 维度 pad + 归一化排序 ----------

    @Test
    void shorterEmbeddingPaddedToDim_andRankingByCosine() throws Exception {
        int dim = 1024;
        try (LuceneVectorStore s = new LuceneVectorStore(tempDir.resolve("dim"), dim)) {
            // A：只给 512 维（e0），写入时 pad-0 到 1024
            float[] a512 = new float[512];
            a512[0] = 1f;
            s.add(chunk(1L, "note", "1", 0, "A512", a512));
            // B：完整 1024 维，方向 e1（与查询 e0 正交）
            float[] b1024 = new float[dim];
            b1024[1] = 1f;
            s.add(chunk(1L, "note", "2", 0, "B1024", b1024));
            s.commitBatch();

            float[] q = new float[dim];
            q[0] = 1f;  // 查询方向 e0
            List<KnowledgeChunk.ScoredChunk> hits = s.search(1L, q, 5);
            assertThat(hits).isNotEmpty();
            assertThat(hits.get(0).chunk().content()).isEqualTo("A512");   // pad 后 cos(q,A)=1 排第一
            assertThat(contentOf(hits)).contains("B1024");
        }
    }

    // ---------- helpers ----------

    private static KnowledgeChunk chunk(long uid, String type, String sourceId, int idx,
                                        String content, float[] embedding) {
        return new KnowledgeChunk(type + "#" + sourceId + "#" + idx, uid, type, sourceId, content, embedding);
    }

    /** 构造前两维的（近似）单位向量，其余补 0；store 内会再 L2 归一化。 */
    private static float[] vec(double x0, double x1) {
        float[] v = new float[D];
        v[0] = (float) x0;
        v[1] = (float) x1;
        return v;
    }

    private static List<String> contentOf(List<KnowledgeChunk.ScoredChunk> hits) {
        List<String> out = new ArrayList<>(hits.size());
        for (KnowledgeChunk.ScoredChunk h : hits) {
            out.add(h.chunk().content());
        }
        return out;
    }
}
