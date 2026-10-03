package com.bupt.mockinterview.rag;

/**
 * Embedding 结果（R2 起携带 token 用量）。
 *
 * @param vector    向量
 * @param tokens    厂商 usage.total_tokens；厂商未返回时为 0，此时看 {@link #estimated}
 * @param estimated true=厂商未回 usage，tokens 为按字符 1:1 的兜底估算
 */
public record Embedding(float[] vector, long tokens, boolean estimated) {
}
