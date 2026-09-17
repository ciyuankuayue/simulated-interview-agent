package com.bupt.mockinterview.rag.experiment;

import com.bupt.mockinterview.rag.FakeEmbedder;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 离线验对比实验骨架本身（召回计算 / docId 去重 / 指标）是否正确；
 * 真实向量器换 FakeEmbedder 即可，框架不变。
 */
public class ComparisonExperimentTest {

    @Test
    void handwrittenCosineRecallOnFakeEmbedder() throws Exception {
        HandcraftedRetriever r = new HandcraftedRetriever(new FakeEmbedder(1024));
        List<Doc> corpus = List.of(
                new Doc("A", "synchronized 关键字 线程同步 monitor 锁升级 偏向锁 轻量级锁 重量级锁"),
                new Doc("B", "MySQL InnoDB 索引 B+树 聚簇索引 二级索引 回表 主键"),
                new Doc("C", "Spring Boot 自动配置 EnableAutoConfiguration 条件化 Conditional 生效"),
                new Doc("D", "Redis RDB 快照 AOF 追加命令 持久化 混合持久化")
        );
        List<QueryCase> queries = List.of(
                new QueryCase("synchronized 的锁升级过程是什么", "A"),
                new QueryCase("InnoDB 的回表是什么", "B")
        );
        ComparisonExperiment.Result res = ComparisonExperiment.run(r, corpus, queries, 2, 0);
        assertEquals(1.0, res.recallAtK(), 0.001, "手写余弦应 100% 命中 ground truth");
        System.out.println(ComparisonExperiment.toMarkdown(List.of(res), 2, corpus.size(), queries.size()));
    }
}
