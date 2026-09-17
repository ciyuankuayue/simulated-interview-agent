package com.bupt.mockinterview.rag.experiment;

import java.util.List;

/**
 * 对比实验的统一检索接口：手写余弦侧与向量库侧都实现它，
 * 这样 {@link ComparisonExperiment} 能无差别地跑两套、出同一张指标表。
 */
public interface Retriever {

    /** 入库一篇文档。 */
    void ingest(String docId, String text) throws Exception;

    /** 返回 top-k 最相似文档（按相似度降序；docId 可能重复——同一文档被切块成多块）。 */
    List<ScoredDoc> search(String query, int topK) throws Exception;

    /** 带相似度的单条结果。 */
    record ScoredDoc(String docId, double score) {
    }
}
