package com.bupt.mockinterview.domain.repository;

import com.bupt.mockinterview.domain.Quota;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface QuotaRepository extends JpaRepository<Quota, Long> {

    Optional<Quota> findByUserId(Long userId);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select q from Quota q where q.user.id = :userId")
    Optional<Quota> lockByUserId(@Param("userId") Long userId);

    /**
     * 条件扣减（R2 并发安全核心，单条 UPDATE 天然原子）：
     * 仅当扣后不超 total 时成功（受影响行 1）；不足返回 0，调用方走 clamp 或 402。
     * 适用于 BYOK 服务费（调用前，绝不超发）与 PLATFORM 常规实扣。
     */
    @Modifying(clearAutomatically = true)
    @Query("update Quota q set q.usedTokens = q.usedTokens + :tokens "
            + "where q.user.id = :userId and q.usedTokens + :tokens <= q.totalTokens")
    int tryChargeTokens(@Param("userId") Long userId, @Param("tokens") long tokens);

    /**
     * PLATFORM 最后一次调用透支时归零到 total（余额变 0，后续预检拦死）。
     * 只在仍有剩余（used &lt; total）时执行，并发下也只生效一次。
     */
    @Modifying(clearAutomatically = true)
    @Query("update Quota q set q.usedTokens = q.totalTokens "
            + "where q.user.id = :userId and q.usedTokens < q.totalTokens")
    int clampUsedToTotal(@Param("userId") Long userId);

    /** ADMIN 充值：增加总额度（无条件，金额已在服务层校验为正）。 */
    @Modifying(clearAutomatically = true)
    @Query("update Quota q set q.totalTokens = q.totalTokens + :tokens where q.user.id = :userId")
    int addTotalTokens(@Param("userId") Long userId, @Param("tokens") long tokens);
}
