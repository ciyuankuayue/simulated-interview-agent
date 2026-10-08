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

    /**
     * R2-T0 批 5 C3：作用域内判定知识块行是否存在（@TenantId 分区谓词自动追加，
     * 调用前必须已把 TenantContext 切到该 userId——见 OrphanIndexInspector）。
     */
    boolean existsByUserIdAndSourceTypeAndSourceId(Long userId, String sourceType, Long sourceId);

    /**
     * 作用域内批量删除知识块行（派生 delete 走 SQM，@TenantId 谓词自动追加）。
     * 禁用裸 delete(entity)（内部 em.find 不走分区谓词）。返回删除行数。
     * <b>R2-T0 批 5 供测试基座/巡检清理；F4「删除资料」联动应复用本方法。</b>
     */
    @org.springframework.transaction.annotation.Transactional
    int deleteByUserIdAndSourceTypeAndSourceId(Long userId, String sourceType, Long sourceId);
}
