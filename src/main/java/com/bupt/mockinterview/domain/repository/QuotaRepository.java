package com.bupt.mockinterview.domain.repository;

import com.bupt.mockinterview.domain.Quota;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface QuotaRepository extends JpaRepository<Quota, Long> {

    Optional<Quota> findByUserId(Long userId);

    /**
     * 条件扣点（R1-3 防超发的最小并发保障；R2 换 @Version/Redis INCR 强化）。
     * 仅当仍有余额时更新成功，返回受影响行数 1；余额不足返回 0，调用方转 402。
     */
    @Modifying(clearAutomatically = true)
    @Query("update Quota q set q.usedPoints = q.usedPoints + :charge "
            + "where q.user.id = :userId and q.usedPoints + :charge <= q.totalPoints")
    int tryCharge(@Param("userId") Long userId, @Param("charge") long charge);
}
