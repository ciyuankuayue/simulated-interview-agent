package com.bupt.mockinterview.rag;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 内存向量库（手写余弦检索的存储端）。MVP 关键路径先用它跑通；
 * L3 对比实验会换成向量库（Redis vector / pgvector）后同查询集比召回率/耗时/代码量。
 */
public class InMemoryVectorStore {

    private final List<KnowledgeChunk> chunks = new CopyOnWriteArrayList<>();

    public void add(KnowledgeChunk chunk) {
        chunks.add(chunk);
    }

    public List<KnowledgeChunk.ScoredChunk> search(float[] queryVec, int topK) {
        List<KnowledgeChunk.ScoredChunk> scored = new ArrayList<>(chunks.size());
        for (KnowledgeChunk c : chunks) {
            scored.add(new KnowledgeChunk.ScoredChunk(c, CosineSimilarity.cosine(queryVec, c.embedding())));
        }
        scored.sort(Comparator.comparingDouble(KnowledgeChunk.ScoredChunk::score).reversed());
        return scored.subList(0, Math.min(topK, scored.size()));
    }

    public int size() {
        return chunks.size();
    }
}
