package com.bupt.mockinterview.domain.repository;

import com.bupt.mockinterview.domain.KnowledgeChunk;
import com.bupt.mockinterview.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface KnowledgeChunkRepository extends JpaRepository<KnowledgeChunk, Long> {
    List<KnowledgeChunk> findBySourceTypeAndSourceId(String sourceType, Long sourceId);
    List<KnowledgeChunk> findBySourceType(String sourceType);

    /** R1-1 多用户隔离：按 user 过滤（R4 向量库迁移时 per-user 分桶）。 */
    List<KnowledgeChunk> findByUser(User user);
    List<KnowledgeChunk> findByUserAndSourceTypeAndSourceId(User user, String sourceType, Long sourceId);
}
