package com.bupt.mockinterview.domain.repository;

import com.bupt.mockinterview.domain.User;
import com.bupt.mockinterview.domain.WeakPoint;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * R2-T0 批 6 审计：无显式 user 谓词的旧 MVP 派生方法（findByResolvedFalse 等 4 个）已删除。
 * 它们虽被 @TenantId 自动分区谓词兜底，但全局调度语义会在跨租户定时扫描（G2）时误用；
 * 将来调度器需要时走 runAsRoot + 显式 tenantId 谓词重新添加（domain.md §9）。
 */
public interface WeakPointRepository extends JpaRepository<WeakPoint, Long> {
    /** R1-1 多用户隔离：所有查询加 user 过滤。 */
    List<WeakPoint> findByUser(User user);
    List<WeakPoint> findByUserAndResolvedFalse(User user);
    List<WeakPoint> findByUserAndResolvedFalseAndNextReviewAtLessThanEqual(User user, Instant now);
    Optional<WeakPoint> findByIdAndUserId(Long id, Long userId);
}
