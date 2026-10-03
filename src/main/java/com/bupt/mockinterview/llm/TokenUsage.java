package com.bupt.mockinterview.llm;

/**
 * 一次 chat 回复的厂商用量（R2 token 计费）。
 *
 * @param promptTokens     输入 token
 * @param completionTokens 输出 token
 * @param totalTokens      总 token（厂商 usage.total_tokens；缺失估算时 = 输入+输出字符估算）
 * @param estimated        true=厂商未回 usage，totalTokens 为字符估算兜底值
 */
public record TokenUsage(long promptTokens, long completionTokens, long totalTokens, boolean estimated) {
}
