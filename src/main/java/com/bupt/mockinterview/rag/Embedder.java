package com.bupt.mockinterview.rag;

/**
 * 向量化抽象。T4 第一版用 {@link FakeEmbedder} 离线验逻辑；
 * 真实路径用 {@link DashscopeEmbedder}（通义 qwen3.7-text-embedding-flash）。
 */
public interface Embedder {
    /** 把一段文本转成定长向量；失败抛异常（如密钥缺失、网络错误）。 */
    float[] embed(String text) throws Exception;
}
