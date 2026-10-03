package com.bupt.mockinterview.rag;

import java.util.Arrays;

/**
 * 离线假向量器：词频哈希到定长维度，仅用于无密钥时单测「切块→余弦→召回」链路是否正确。
 * 不具语义，不能用于真实 RAG；真实路径换 {@link DashscopeEmbedder}。
 */
public class FakeEmbedder implements Embedder {

    private final int dim;

    public FakeEmbedder(int dim) {
        this.dim = dim;
    }

    @Override
    public String name() {
        return "fake:local-hash";
    }

    @Override
    public Embedding embed(String text) {
        float[] v = new float[dim];
        for (String w : text.toLowerCase().split("\\s+")) {
            int h = Math.floorMod(w.hashCode(), dim);
            v[h] += 1.0f;
        }
        // 离线假向量不计量（tokens=0、非估算 → RagService 跳过扣费）
        return new Embedding(v, 0L, false);
    }
}
