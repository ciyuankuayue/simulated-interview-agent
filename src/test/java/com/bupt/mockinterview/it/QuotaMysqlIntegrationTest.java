package com.bupt.mockinterview.it;

import com.bupt.mockinterview.billing.QuotaService;
import com.bupt.mockinterview.billing.QuotaProperties;
import com.bupt.mockinterview.billing.UsageScene;
import com.bupt.mockinterview.domain.KeyMode;
import com.bupt.mockinterview.domain.Quota;
import com.bupt.mockinterview.domain.User;
import com.bupt.mockinterview.domain.repository.LlmUsageLogRepository;
import com.bupt.mockinterview.domain.repository.QuotaRepository;
import com.bupt.mockinterview.security.TenantContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.junit.jupiter.EnabledIf;

import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R5-1：配额计费在真实 MySQL InnoDB 下的回归（条件 UPDATE 行锁不超发）。
 * <p>H2 的 MVCC 与 InnoDB 行锁行为可能不同；本类把 R2-T0 批 4 的并发实证搬到真机。
 * <p>strict=true：所有 worker 线程必须先 {@link TenantContext#set} 到目标用户，
 * 否则 resolver 抛 {@code TENANT_CONTEXT_MISSING}。
 * <p>不加类级 {@code @Transactional}：{@link QuotaService#settlePlatformCall} 是 REQUIRES_NEW，
 * 类事务会让独立事务数据对外不可见。
 */
@EnabledIf("#{T(com.bupt.mockinterview.it.MysqlItSupport).isMysqlReachable()}")
class QuotaMysqlIntegrationTest extends MysqlItSupport {

    private static final long POINT = QuotaProperties.TOKENS_PER_POINT;

    @Autowired private QuotaService quotaService;
    @Autowired private QuotaRepository quotaRepo;
    @Autowired private LlmUsageLogRepository usageRepo;

    private User newUserWithQuota(String name, long totalTokens) {
        User user = createUser(name);
        TenantContext.set(user.getId());
        try {
            quotaRepo.save(new Quota(user, totalTokens));
        } finally {
            TenantContext.clear();
        }
        return user;
    }

    @Test
    void platformSettle_chargesRealTokens_andWritesUsageLog_onInnoDB() {
        User user = newUserWithQuota("r5-mysql-settle", 1000);
        asUser(user);

        quotaService.settlePlatformCall(user.getId(), UsageScene.FOLLOWUP, "deepseek-flash",
                30, 20, 50, false);

        Quota q = quotaRepo.findByUserId(user.getId()).orElseThrow();
        assertThat(q.getUsedTokens()).isEqualTo(50);
        assertThat(q.remaining()).isEqualTo(950);
        assertThat(quotaService.recentUsage(user.getId(), 10)).hasSize(1);
    }

    @Test
    void byokCharge_fixedFee_neverOverdrafts_onInnoDB() {
        User user = newUserWithQuota("r5-mysql-byok", 2 * POINT);
        asUser(user);

        assertThat(quotaService.chargeForByokCall(user.getId())).isTrue();
        assertThat(quotaService.getByUser(user.getId()).remaining()).isEqualTo(POINT);
        assertThat(quotaService.chargeForByokCall(user.getId())).isTrue();
        assertThat(quotaService.getByUser(user.getId()).remaining()).isZero();
        // 第三次：条件 UPDATE 不命中 → false，不超发
        assertThat(quotaService.chargeForByokCall(user.getId())).isFalse();
        assertThat(quotaRepo.findByUserId(user.getId()).orElseThrow().getUsedTokens()).isEqualTo(2 * POINT);
    }

    /**
     * R2-T0 C1 并发实证真机版：50 线程 × 3 轮栅栏同发 = 150 次混合扣减
     * （PLATFORM 每次 7 积分 / BYOK 每次 1 积分，各 75 次），总额度 300 积分远不足以覆盖。
     * InnoDB 行锁下不变量：无异常、无负余额、恰好扣满 300 积分、旁观者零变化。
     */
    @Test
    void concurrentMixed_50x3_neverOverdrafts_onInnoDB() throws InterruptedException {
        User user = newUserWithQuota("r5-mysql-concurrent", 300 * POINT);
        User bystander = newUserWithQuota("r5-mysql-bystander", 500);

        int threads = 50;
        int rounds = 3;
        long platformCost = 7L * POINT;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CyclicBarrier barrier = new CyclicBarrier(threads);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger errors = new AtomicInteger();
        AtomicInteger negativeRemaining = new AtomicInteger();
        AtomicInteger byokAttempts = new AtomicInteger();
        AtomicInteger byokSuccess = new AtomicInteger();
        AtomicInteger platformAttempts = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    for (int round = 0; round < rounds; round++) {
                        barrier.await(60, TimeUnit.SECONDS);
                        boolean platform = ((idx + round) & 1) == 0;
                        TenantContext.set(user.getId());   // strict 必备
                        try {
                            if (platform) {
                                platformAttempts.incrementAndGet();
                                quotaService.settlePlatformCall(user.getId(), UsageScene.FOLLOWUP,
                                        "deepseek-flash", 5, 2, platformCost, false);
                            } else {
                                byokAttempts.incrementAndGet();
                                if (quotaService.chargeForByokCall(user.getId())) {
                                    byokSuccess.incrementAndGet();
                                }
                            }
                            if (quotaService.getByUser(user.getId()).remaining() < 0L) {
                                negativeRemaining.incrementAndGet();
                            }
                        } finally {
                            TenantContext.clear();
                        }
                    }
                } catch (InterruptedException | BrokenBarrierException | java.util.concurrent.TimeoutException e) {
                    Thread.currentThread().interrupt();
                    errors.incrementAndGet();
                } catch (Exception e) {
                    errors.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }
        assertThat(done.await(120, TimeUnit.SECONDS)).isTrue();
        pool.shutdown();

        assertThat(errors.get()).as("无异常逃逸").isZero();
        assertThat(negativeRemaining.get()).as("无负余额").isZero();
        assertThat(platformAttempts.get()).isEqualTo(75);
        assertThat(byokAttempts.get()).isEqualTo(75);

        asUser(user);
        Quota q = quotaRepo.findByUserId(user.getId()).orElseThrow();
        assertThat(q.getUsedTokens()).isEqualTo(300 * POINT);       // 恰好扣满
        assertThat(q.remaining()).isZero();

        // 旁观者零变化
        asUser(bystander);
        Quota bq = quotaRepo.findByUserId(bystander.getId()).orElseThrow();
        assertThat(bq.getUsedTokens()).isZero();
        assertThat(bq.remaining()).isEqualTo(500L);
    }
}
