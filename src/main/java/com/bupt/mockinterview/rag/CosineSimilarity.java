package com.bupt.mockinterview.rag;

/**
 * 手写余弦相似度（L3 对比实验的基线实现，决策 #10）。
 * 不引任何向量库，纯 float[] 点积。后续换向量库时同输入同查询集比召回率/耗时/代码量。
 */
public final class CosineSimilarity {

    private CosineSimilarity() {}

    public static double cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length == 0 || a.length != b.length) {
            throw new IllegalArgumentException("向量不能为空且长度必须一致");
        }
        double dot = 0.0, na = 0.0, nb = 0.0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        double denom = Math.sqrt(na) * Math.sqrt(nb);
        return denom == 0.0 ? 0.0 : dot / denom;
    }
}
