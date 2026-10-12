package com.bupt.mockinterview.it;

import com.bupt.mockinterview.domain.User;
import com.bupt.mockinterview.rag.KnowledgeChunk;
import com.bupt.mockinterview.rag.LuceneVectorStore;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.junit.jupiter.EnabledIf;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R5-1：RAG 向量持久化 + 用户隔离在真实落盘 Lucene FSDirectory 下的回归。
 * <p>H2 测试用 ByteBuffersDirectory（内存），不落盘；本类用真实 FSDirectory（TempDir），
 * 验证：① 关闭索引后重开仍能检索到旧块（持久化）；② userId pre-filter 不串户。
 */
@EnabledIf("#{T(com.bupt.mockinterview.it.MysqlItSupport).isMysqlReachable()}")
class RagMysqlIntegrationTest extends MysqlItSupport {

    private static final int DIM = 1024;

    private static float[] basis(int axis) {
        float[] v = new float[DIM];
        v[axis] = 1f;
        return v;
    }

    @Test
    void fsDirectory_persistsAcrossReopen() throws Exception {
        User a = createUser("r5-rag-persist-a");
        // 独立临时目录，避免 close 时的锁在 Windows 下影响其他用例
        Path persistDir = Files.createTempDirectory("rag-persist-it");
        try (LuceneVectorStore store = new LuceneVectorStore(persistDir, DIM)) {
            store.beginReplace(a.getId(), "note", "p1");
            store.add(new KnowledgeChunk("p1#0", a.getId(), "note", "p1", "A 的持久化块 0", basis(0)));
            store.add(new KnowledgeChunk("p1#1", a.getId(), "note", "p1", "A 的持久化块 1", basis(0)));
            store.commitBatch();
        }
        // 用同目录重开新 writer/reader → 旧块必须仍在
        try (LuceneVectorStore reopened = new LuceneVectorStore(persistDir, DIM)) {
            List<KnowledgeChunk.ScoredChunk> hits = reopened.search(a.getId(), basis(0), 5);
            assertThat(hits).as("重开后仍能检索到 A 的块").hasSize(2);
            assertThat(hits).allSatisfy(s -> assertThat(s.chunk().userId()).isEqualTo(a.getId()));
        }
    }

    @Test
    void userPreFilter_preventsCrossTenantRetrieval() throws Exception {
        User a = createUser("r5-rag-iso-a");
        User b = createUser("r5-rag-iso-b");

        // 独立临时目录，每个用例自建 LuceneVectorStore，不依赖 Spring bean 状态
        Path isoDir = Files.createTempDirectory("rag-iso-it");
        try (LuceneVectorStore store = new LuceneVectorStore(isoDir, DIM)) {
            // A 入库：与 basis[0] 同向（查询最该命中）
            store.beginReplace(a.getId(), "note", "a1");
            store.add(new KnowledgeChunk("a1#0", a.getId(), "note", "a1", "A 机密：仅 A 可见", basis(0)));
            store.commitBatch();

            // B 入库：与 basis[0] 正交
            store.beginReplace(b.getId(), "note", "b1");
            store.add(new KnowledgeChunk("b1#0", b.getId(), "note", "b1", "B 自己的笔记", basis(1)));
            store.commitBatch();

            // B 用 basis[0] 查询（与 A 的块同向）→ pre-filter 下绝不能返回 A 的块
            List<KnowledgeChunk.ScoredChunk> bView = store.search(b.getId(), basis(0), 5);
            assertThat(bView).allSatisfy(s -> {
                assertThat(s.chunk().userId()).isEqualTo(b.getId());
                assertThat(s.chunk().content()).doesNotContain("A 机密");
            });

            // A 自己查询必命中自己的块
            List<KnowledgeChunk.ScoredChunk> aView = store.search(a.getId(), basis(0), 5);
            assertThat(aView).isNotEmpty();
            assertThat(aView.get(0).chunk().userId()).isEqualTo(a.getId());
            assertThat(aView.get(0).chunk().content()).contains("A 机密");
        }
    }
}
