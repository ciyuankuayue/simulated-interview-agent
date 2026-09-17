package com.bupt.mockinterview.rag.experiment;

import com.bupt.mockinterview.rag.Embedder;
import com.bupt.mockinterview.rag.InMemoryVectorStore;
import com.bupt.mockinterview.rag.RagService;

import java.util.List;
import java.util.stream.Collectors;

/** 手写余弦侧：直接复用 T4 的 RagService（切块→向量化→内存余弦）。 */
public class HandcraftedRetriever implements Retriever {

    private final RagService rag;

    public HandcraftedRetriever(Embedder embedder) {
        this.rag = new RagService(embedder, new InMemoryVectorStore());
    }

    @Override
    public void ingest(String docId, String text) throws Exception {
        rag.ingest(text, "corpus", docId);
    }

    @Override
    public List<ScoredDoc> search(String query, int topK) throws Exception {
        return rag.retrieve(query, topK).stream()
                .map(s -> new ScoredDoc(s.chunk().sourceId(), s.score()))
                .collect(Collectors.toList());
    }
}
