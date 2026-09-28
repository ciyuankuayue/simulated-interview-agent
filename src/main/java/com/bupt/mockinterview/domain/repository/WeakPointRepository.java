package com.bupt.mockinterview.domain.repository;

import com.bupt.mockinterview.domain.User;
import com.bupt.mockinterview.domain.WeakPoint;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface WeakPointRepository extends JpaRepository<WeakPoint, Long> {
    List<WeakPoint> findByJdProfileId(Long jdProfileId);
    List<WeakPoint> findByNextReviewAtLessThanEqual(Instant now);

    /** 复习队列（未出队）。 */
    List<WeakPoint> findByResolvedFalse();

    /** 已到复习时间且未出队。 */
    List<WeakPoint> findByResolvedFalseAndNextReviewAtLessThanEqual(Instant now);

    /** R1-1 多用户隔离：所有查询加 user 过滤。 */
    List<WeakPoint> findByUser(User user);
    List<WeakPoint> findByUserAndResolvedFalse(User user);
    List<WeakPoint> findByUserAndResolvedFalseAndNextReviewAtLessThanEqual(User user, Instant now);
    Optional<WeakPoint> findByIdAndUserId(Long id, Long userId);
}
