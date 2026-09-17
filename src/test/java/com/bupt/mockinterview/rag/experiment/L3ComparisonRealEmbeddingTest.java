package com.bupt.mockinterview.rag.experiment;

import com.bupt.mockinterview.rag.DashscopeEmbedder;
import com.bupt.mockinterview.rag.Embedder;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * L3 对比实验（真实 embedding）：同语料 + 同查询集 + ground truth，
 * 分别跑「手写余弦」与「Lucene HNSW 向量库」两套 Retriever，出召回率 / 单查询耗时 / 代码量三指标。
 *
 * 决策 #10 关键路径产出，结论直接进 demo 讲。
 *
 * 运行（需本机 set 密钥，沙盒无限流不跑）：
 *   PowerShell:  $env:DASHSCOPE_API_KEY="sk-xxx"; .\mvnw.cmd test -Dtest=L3ComparisonRealEmbeddingTest
 *   Git Bash:    DASHSCOPE_API_KEY=sk-xxx ./mvnw.cmd test -Dtest=L3ComparisonRealEmbeddingTest
 * 未 set 密钥时整类自动跳过（Assumptions）。结果同时打印并写出 docs/l3-comparison.md。
 */
public class L3ComparisonRealEmbeddingTest {

    private static final String CORPUS = "corpus/java-backend-knowledge.md";
    private static final int TOP_K = 3;
    // 代码量（行）为近似值，按各自实现真实行数估算；用于决策级对比，非精确统计
    private static final int HANDCRAFTED_LINES = 30;
    private static final int LUCENE_LINES = 100;

    @Test
    void l3RealEmbeddingComparison() throws Exception {
        String key = System.getenv("DASHSCOPE_API_KEY");
        Assumptions.assumeTrue(key != null && !key.isBlank(),
                "未设置 DASHSCOPE_API_KEY，跳过真实 embedding 对比（本地 set 后跑）");

        OkHttpClient client = new OkHttpClient();
        Embedder embedder = new DashscopeEmbedder(client,
                "https://dashscope.aliyuncs.com/compatible-mode/v1",
                key, "qwen3.7-text-embedding-flash");

        List<Doc> corpus = CorpusLoader.load(CORPUS);
        List<QueryCase> queries = buildQueries();

        Retriever hand = new HandcraftedRetriever(embedder);
        Retriever lucene = new LuceneHnswRetriever(embedder);

        ComparisonExperiment.Result r1 = ComparisonExperiment.run(hand, corpus, queries, TOP_K, HANDCRAFTED_LINES);
        ComparisonExperiment.Result r2 = ComparisonExperiment.run(lucene, corpus, queries, TOP_K, LUCENE_LINES);

        String md = ComparisonExperiment.toMarkdown(List.of(r1, r2), TOP_K, corpus.size(), queries.size());
        System.out.println("\n===== L3 真实 embedding 对比（" + corpus.size() + " 篇语料 / "
                + queries.size() + " 查询）=====\n" + md);

        Path out = Path.of("docs/l3-comparison.md");
        Files.createDirectories(out.getParent());
        Files.writeString(out, md);
        System.out.println("已写出 " + out.toAbsolutePath());
    }

    /** 15 条贴合该大纲的面试官问题，每个标注应命中的知识点原文（leaf 行），用 slug 对齐 Doc.id。 */
    private static List<QueryCase> buildQueries() {
        List<QueryCase> qs = new ArrayList<>();
        qs.add(q("synchronized 和 Lock 有什么区别？各自适用场景？", "synchronized、Lock、AQS"));
        qs.add(q("MySQL 的 B+树索引为什么快？聚簇和非聚簇索引区别？", "InnoDB、B+树、聚簇/非聚簇索引"));
        qs.add(q("Redis 缓存穿透、击穿、雪崩分别怎么解决？", "缓存穿透/击穿/雪崩"));
        qs.add(q("Spring 的 @Transactional 事务为什么失效？", "@Transactional：传播、隔离、事务失效场景"));
        qs.add(q("Spring Boot 自动装配原理是什么？starter 机制？", "自动装配、starter 机制"));
        qs.add(q("RAG 的完整链路是什么？分块和重排怎么做？", "完整链路：文档解析→分块→向量化→检索→生成"));
        qs.add(q("什么是向量数据库？和传统数据库区别？", "Qdrant/Milvus、向量索引、相似度检索"));
        qs.add(q("JVM 的 G1 和 ZGC 垃圾回收器有什么区别？", "GC：存活判断、GC 算法、G1/ZGC"));
        qs.add(q("TCP 三次握手和四次挥手分别是什么？", "TCP 三次握手四次挥手、滑动窗口、拥塞控制"));
        qs.add(q("线程池 ThreadPoolExecutor 的核心参数和拒绝策略？", "线程池 ThreadPoolExecutor、拒绝策略"));
        qs.add(q("HashMap 和 ConcurrentHashMap 有什么区别？", "Map：HashMap、ConcurrentHashMap"));
        qs.add(q("MySQL 的 MVCC 多版本并发控制怎么实现？", "ACID、隔离级别、MVCC"));
        qs.add(q("Java 后端怎么用 SSE 做流式输出？", "SSE 流式输出、WebSocket"));
        qs.add(q("Agent 的 Function-call 工具调用是什么？", "规划、记忆、Function-call 工具调用"));
        qs.add(q("怎么用 Redis 实现分布式锁？", "分布式锁"));
        return qs;
    }

    private static QueryCase q(String query, String leafText) {
        return new QueryCase(query, CorpusLoader.slug(leafText));
    }
}
