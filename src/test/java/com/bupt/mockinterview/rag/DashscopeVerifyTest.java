package com.bupt.mockinterview.rag;

import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 真实通义 embedding 联调验证（需本机 set DASHSCOPE_API_KEY）。
 * 沙盒无限流、无密钥时整类跳过（assumeTrue），不会污染 `mvnw test`。
 *
 * 跑法（repo 根目录）：
 *   PowerShell:  $env:DASHSCOPE_API_KEY="sk-xxx"; .\mvnw.cmd test -Dtest=DashscopeVerifyTest
 *   Git Bash:    DASHSCOPE_API_KEY=sk-xxx ./mvnw.cmd test -Dtest=DashscopeVerifyTest
 *
 * 验证点：
 *   1) embedding 能调通、返回维度（断言 1024，若真实返回不符以实际为准改 DashscopeEmbedder.DIM）
 *   2) 检索命中正确块（query 应 top-1 命中对应 sourceId 的资料）
 */
public class DashscopeVerifyTest {

    private static final String DEFAULT_BASE = "https://dashscope.aliyuncs.com/compatible-mode/v1";
    private static final String DEFAULT_MODEL = "qwen3.7-text-embedding-flash";

    @Test
    void verifyDashscopeEmbeddingAndRetrieval() throws Exception {
        String key = System.getenv("DASHSCOPE_API_KEY");
        Assumptions.assumeTrue(key != null && !key.isBlank(),
                "未设置 DASHSCOPE_API_KEY，跳过真实联调（离线逻辑请用 RagServiceTest + FakeEmbedder）");

        String base = System.getenv().getOrDefault("DASHSCOPE_BASE_URL", DEFAULT_BASE);
        String model = System.getenv().getOrDefault("DASHSCOPE_EMBED_MODEL", DEFAULT_MODEL);

        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(60, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .build();
        DashscopeEmbedder embedder = new DashscopeEmbedder(client, base, key, model);

        // 1) 探维度
        float[] probe = embedder.embed("探维度用的一句话");
        System.out.println("[verify] embedding dim = " + probe.length);
        if (probe.length != 1024) {
            System.out.println("[verify] ⚠️ 真实维度 != 1024，请改 DashscopeEmbedder.DIM 与 RagService 预期");
        }

        // 2) 建小语料（4 块，sourceId = A/B/C/D 便于断言命中）
        RagService rag = new RagService(embedder, new InMemoryVectorStore());
        rag.ingest("Java 的 synchronized 关键字用于线程同步，保证方法或代码块同一时刻只有一个线程执行；"
                + "底层依赖 monitor 锁，JDK 1.6 后做了锁升级优化：无锁→偏向锁→轻量级锁→重量级锁。", "note", "A");
        rag.ingest("MySQL InnoDB 的索引使用 B+ 树，聚簇索引叶子节点存整行数据，二级索引叶子节点存主键值；"
                + "回表指二级索引查到主键后再去聚簇索引取完整行。", "note", "B");
        rag.ingest("Spring Boot 自动配置原理是 @EnableAutoConfiguration 配合 AutoConfiguration.imports 批量加载条件化配置类，"
                + "按 @Conditional 条件按需生效。", "note", "C");
        rag.ingest("Redis 的 RDB 是定时快照、AOF 是追加命令日志；AOF 持久性更好但文件大，混合持久化兼顾两者。", "note", "D");

        // 3) 两个查询，断言 top-1 命中正确块
        assertTopHit(rag, "synchronized 的锁升级过程是什么？", "A");
        assertTopHit(rag, "InnoDB 的回表是什么？", "B");
    }

    private void assertTopHit(RagService rag, String query, String expectSourceId) throws Exception {
        List<KnowledgeChunk.ScoredChunk> top = rag.retrieve(query, 2);
        KnowledgeChunk.ScoredChunk hit = top.get(0);
        System.out.printf("[verify] query=%-40s top1=%s score=%.4f -> %s%n",
                query, hit.chunk().sourceId(), hit.score(),
                hit.chunk().content().length() > 30 ? hit.chunk().content().substring(0, 30) + "..." : hit.chunk().content());
        if (!hit.chunk().sourceId().equals(expectSourceId)) {
            System.out.println("[verify] ❌ 命中错误块，期望 " + expectSourceId + " 实际 " + hit.chunk().sourceId());
            throw new AssertionError("检索未命中预期块: query=" + query + " expect=" + expectSourceId + " actual=" + hit.chunk().sourceId());
        }
        System.out.println("[verify] ✅ 命中正确块 " + expectSourceId);
    }
}
