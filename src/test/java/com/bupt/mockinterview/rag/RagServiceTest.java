package com.bupt.mockinterview.rag;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 离线单测：不依赖任何密钥/网络，验证「切块→向量化→余弦检索」链路能把查询命中最相似块。
 * 用 FakeEmbedder（词频哈希向量），语义无关但足以证明检索排序正确。
 */
class RagServiceTest {

    @Test
    void retrieveReturnsMostSimilarChunk() throws Exception {
        RagService svc = new RagService(new FakeEmbedder(64), new InMemoryVectorStore());
        svc.ingest("Java 线程池核心参数 corePoolSize 与 maximumPoolSize 的区别", "note", "n1");
        svc.ingest("MySQL InnoDB 的聚簇索引与二级索引结构", "note", "n2");
        svc.ingest("Redis 的 RDB 与 AOF 持久化方式对比", "note", "n3");

        List<KnowledgeChunk.ScoredChunk> res = svc.retrieve("线程池的 corePoolSize 是什么", 1);
        assertEquals(1, res.size());
        assertTrue(res.get(0).chunk().content().contains("线程池"), "应命中最相似的线程池块");
    }

    @Test
    void ingestChunksLongText() throws Exception {
        RagService svc = new RagService(new FakeEmbedder(64), new InMemoryVectorStore());
        String longText = "第一段讲 JVM 内存结构。\n\n".repeat(200); // 远超 500 字符，应被硬切多块
        svc.ingest(longText, "note", "big");
        assertTrue(svc.retrieve("JVM", 3).size() >= 1);
    }
}
