package com.bupt.mockinterview.rag;

/**
 * 一段被向量化后的知识块（运行时对象；后续可持久化到 domain 的 knowledge_chunk 实体，T8 范畴）。
 */
public record KnowledgeChunk(
        String id,
        String sourceType,
        String sourceId,
        String content,
        float[] embedding
) {
    /** 带相似度分数的检索结果。 */
    public record ScoredChunk(KnowledgeChunk chunk, double score) {}
}
