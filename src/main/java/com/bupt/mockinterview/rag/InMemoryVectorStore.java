package com.bupt.mockinterview.rag;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 内存向量库（手写余弦检索的存储端）。MVP 关键路径先用它跑通；
 * R4 起作为离线单测/实验的轻量实现，生产换 {@code LuceneVectorStore}。
 *
 * <p>线程安全：CopyOnWriteArrayList 读写无锁；beginReplace→add→commitBatch 在内存实现里
 * 立即生效（无延迟可见性），与 Lucene 的批次提交语义不同，属预期。
 */
public class InMemoryVectorStore implements VectorStore {

    private final List<KnowledgeChunk> chunks = new CopyOnWriteArrayList<>();

    @Override
    public void add(KnowledgeChunk chunk) {
        chunks.add(chunk);
    }

    /** 按 userId 过滤后再做余弦排序。userId=null 不过滤（离线/实验）。 */
    @Override
    public List<KnowledgeChunk.ScoredChunk> search(Long userId, float[] queryVec, int topK) {
        List<KnowledgeChunk.ScoredChunk> scored = new ArrayList<>(chunks.size());
        for (KnowledgeChunk c : chunks) {
            if (userId != null && !userId.equals(c.userId())) {
                continue;
            }
            scored.add(new KnowledgeChunk.ScoredChunk(c, CosineSimilarity.cosine(queryVec, c.embedding())));
        }
        scored.sort(Comparator.comparingDouble(KnowledgeChunk.ScoredChunk::score).reversed());
        return scored.subList(0, Math.min(topK, scored.size()));
    }

    /** AND 语义：userId（非 null 时）+ sourceType + sourceId 全匹配才删。 */
    @Override
    public void deleteBySource(Long userId, String sourceType, String sourceId) {
        chunks.removeIf(c ->
                (userId == null || userId.equals(c.userId()))
                        && sourceType.equals(c.sourceType())
                        && sourceId.equals(c.sourceId())
        );
    }

    @Override
    public void deleteAll() {
        chunks.clear();
    }

    @Override
    public int size() {
        return chunks.size();
    }

    @Override
    public void close() { /* 内存无需关闭 */ }
}
