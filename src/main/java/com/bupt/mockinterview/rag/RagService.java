package com.bupt.mockinterview.rag;

import java.util.List;

/**
 * RAG 编排：入库（切块→向量化→存）与检索（查询向量化→top-k 余弦）。
 * Embedder 注入式，便于离线用 {@link FakeEmbedder} 单测、线上换 {@link DashscopeEmbedder}。
 */
public class RagService {

    private final Embedder embedder;
    private final InMemoryVectorStore store;
    private final int maxChars;
    private final int overlap;

    public RagService(Embedder embedder, InMemoryVectorStore store) {
        this(embedder, store, 500, 50);
    }

    public RagService(Embedder embedder, InMemoryVectorStore store, int maxChars, int overlap) {
        this.embedder = embedder;
        this.store = store;
        this.maxChars = maxChars;
        this.overlap = overlap;
    }

    /** 把一份资料（note/resume/project_doc 文本）切块向量化后入库。 */
    public void ingest(String text, String sourceType, String sourceId) throws Exception {
        List<String> pieces = TextChunker.chunk(text, maxChars, overlap);
        for (int i = 0; i < pieces.size(); i++) {
            float[] vec = embedder.embed(pieces.get(i));
            store.add(new KnowledgeChunk(sourceType + "#" + sourceId + "#" + i, sourceType, sourceId, pieces.get(i), vec));
        }
    }

    /** 给定查询返回 top-k 最相似块（按余弦降序）。 */
    public List<KnowledgeChunk.ScoredChunk> retrieve(String query, int topK) throws Exception {
        float[] q = embedder.embed(query);
        return store.search(q, topK);
    }
}
