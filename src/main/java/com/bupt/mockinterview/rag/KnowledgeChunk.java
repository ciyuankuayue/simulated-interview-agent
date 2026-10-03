package com.bupt.mockinterview.rag;

/**
 * 一段被向量化后的知识块（运行时对象；R4 起持久化到 Lucene FSDirectory 索引，
 * 非 domain 的 knowledge_chunk 死表）。
 *
 * @param userId 所属用户；null=离线/实验路径（检索与删除均不加用户条件）
 */
public record KnowledgeChunk(
        String id,
        Long userId,
        String sourceType,
        String sourceId,
        String content,
        float[] embedding
) {
    /** 带相似度分数的检索结果。 */
    public record ScoredChunk(KnowledgeChunk chunk, double score) {}
}
