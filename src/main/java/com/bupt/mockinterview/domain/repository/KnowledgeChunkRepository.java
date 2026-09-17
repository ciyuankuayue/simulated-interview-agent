package com.bupt.mockinterview.domain.repository;

import com.bupt.mockinterview.domain.KnowledgeChunk;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface KnowledgeChunkRepository extends JpaRepository<KnowledgeChunk, Long> {
    List<KnowledgeChunk> findBySourceTypeAndSourceId(String sourceType, Long sourceId);
    List<KnowledgeChunk> findBySourceType(String sourceType);
}
