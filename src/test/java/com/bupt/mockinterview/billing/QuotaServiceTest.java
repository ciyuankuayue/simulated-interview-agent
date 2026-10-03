package com.bupt.mockinterview.billing;

import com.bupt.mockinterview.domain.KeyMode;
import com.bupt.mockinterview.domain.Quota;
import com.bupt.mockinterview.domain.User;
import com.bupt.mockinterview.domain.repository.LlmUsageLogRepository;
import com.bupt.mockinterview.domain.repository.QuotaRepository;
import com.bupt.mockinterview.domain.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * R2 token 计费：真实 JPA + H2 跑条件更新 SQL。
 * 注意：settlePlatformCall 是 REQUIRES_NEW，本类不能加类级 @Transactional
 * （独立事务要求调用数据已提交）；测试用户用唯一名，H2 每 context 重建无需清理。
 */
@SpringBootTest
class QuotaServiceTest {

    @Autowired
    private QuotaService quotaService;
    @Autowired
    private QuotaRepository quotaRepo;
    @Autowired
    private UserRepository userRepo;
    @Autowired
    private LlmUsageLogRepository usageRepo;

    private User newUserWithQuota(String name, long totalTokens) {
        User user = userRepo.save(new User(name, "hash"));
        quotaRepo.save(new Quota(user, totalTokens));
        return user;
    }

    @Test
    void byokCharge_neverOverdrafts_andGrantIfAbsentIdempotent() {
        User user = newUserWithQuota("quota-edge-user-r2", 2);

        assertThat(quotaService.chargeForByokCall(user.getId())).isTrue();
        assertThat(quotaService.getByUser(user.getId()).remaining()).isEqualTo(1);
        assertThat(quotaService.chargeForByokCall(user.getId())).isTrue();
        assertThat(quotaService.getByUser(user.getId()).remaining()).isZero();

        // 第三次：条件更新不命中 → false，used 仍为 2（不会负数/超发）
        assertThat(quotaService.chargeForByokCall(user.getId())).isFalse();
        Quota reread = quotaRepo.findByUserId(user.getId()).orElseThrow();
        assertThat(reread.getUsedTokens()).isEqualTo(2);
        assertThat(reread.remaining()).isZero();

        // 幂等补建：已有额度时不动 total
        assertThat(quotaService.grantIfAbsent(user).getTotalTokens()).isEqualTo(2);
    }

    @Test
    void grantForNewUser_givesConfiguredSignupBonusTokens() {
        User user = userRepo.save(new User("quota-bonus-user-r2", "hash"));
        Quota quota = quotaService.grantForNewUser(user);
        assertThat(quota.getTotalTokens()).isEqualTo(500_000L);
        assertThat(quota.getUsedTokens()).isZero();
    }

    @Test
    void platformSettle_chargesRealTokens_andWritesUsageLog() {
        User user = newUserWithQuota("quota-settle-user", 1000);

        quotaService.settlePlatformCall(user.getId(), UsageScene.FOLLOWUP, "deepseek-flash",
                30, 20, 50, false);

        Quota q = quotaRepo.findByUserId(user.getId()).orElseThrow();
        assertThat(q.getUsedTokens()).isEqualTo(50);
        assertThat(q.remaining()).isEqualTo(950);
        var logs = quotaService.recentUsage(user.getId(), 10);
        assertThat(logs).hasSize(1);
        var log = logs.get(0);
        assertThat(log.getScene()).isEqualTo(UsageScene.FOLLOWUP);
        assertThat(log.getKeyMode()).isEqualTo(KeyMode.PLATFORM);
        assertThat(log.getChargedTokens()).isEqualTo(50);
        assertThat(log.isEstimated()).isFalse();
    }

    @Test
    void platformSettle_lastCallOverdraft_isClampedToTotal_notNegative() {
        User user = newUserWithQuota("quota-clamp-user", 30);

        // 余额 30，实扣 50：tryCharge 失败 → clamp 归零到 30
        quotaService.settlePlatformCall(user.getId(), UsageScene.SCORE, "deepseek-reasoner",
                40, 10, 50, false);

        Quota q = quotaRepo.findByUserId(user.getId()).orElseThrow();
        assertThat(q.getUsedTokens()).isEqualTo(30);
        assertThat(q.remaining()).isZero();
        // 流水照记真实用量（透支不追讨，但明细在）
        assertThat(usageRepo.findAll()).anyMatch(l -> l.getUserId().equals(user.getId())
                && l.getTotalTokens() == 50 && l.getChargedTokens() == 50);
    }

    @Test
    void ensureAvailable_zeroBalance_throwsWithModeSpecificMessage() {
        User user = newUserWithQuota("quota-zero-user", 0);
        assertThatThrownBy(() -> quotaService.ensureAvailable(user.getId(), KeyMode.PLATFORM))
                .isInstanceOf(QuotaExhaustedException.class)
                .hasMessageContaining("联系管理员充值");
        assertThatThrownBy(() -> quotaService.ensureAvailable(user.getId(), KeyMode.BYOK))
                .isInstanceOf(QuotaExhaustedException.class)
                .hasMessageContaining("切回平台模式");
    }

    @Test
    void byokChat_recordsRealUsageButChargesServiceFeeOnce() {
        User user = newUserWithQuota("quota-byok-log-user", 100);
        assertThat(quotaService.chargeForByokCall(user.getId())).isTrue();

        // 用户 key 上真实烧了 1234 token，平台只记流水、不再扣额度
        quotaService.recordByokChat(user.getId(), UsageScene.FOLLOWUP, "deepseek-flash",
                1000, 234, 1234, false);

        Quota q = quotaRepo.findByUserId(user.getId()).orElseThrow();
        assertThat(q.getUsedTokens()).isEqualTo(1);            // 只有服务费
        var log = quotaService.recentUsage(user.getId(), 1).get(0);
        assertThat(log.getKeyMode()).isEqualTo(KeyMode.BYOK);
        assertThat(log.getTotalTokens()).isEqualTo(1234);      // 真实用量展示
        assertThat(log.getChargedTokens()).isEqualTo(1);       // 实际扣减
    }

    @Test
    void grantTokens_adminTopup_increasesTotalAndKeepsUsed() {
        User user = newUserWithQuota("quota-admin-topup-user", 100);
        quotaService.settlePlatformCall(user.getId(), UsageScene.CHAT, "deepseek-flash",
                40, 10, 50, false);

        Quota topped = quotaService.grantTokens(user.getId(), 200);
        assertThat(topped.getTotalTokens()).isEqualTo(300);
        assertThat(topped.getUsedTokens()).isEqualTo(50);
        assertThat(topped.remaining()).isEqualTo(250);

        assertThatThrownBy(() -> quotaService.grantTokens(user.getId(), 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * R2-2 并发压测：20 线程各扣 10 token，总额度只有 100。
     * 条件 UPDATE 原子性下：成功扣减的总额恰好 = 100，透支全部 clamp 归零，
     * 不超发、无负余额。
     */
    @Test
    void concurrentSettle_neverOverdrafts() throws InterruptedException {
        User user = newUserWithQuota("quota-concurrent-user", 100);
        int threads = 20;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger errors = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    quotaService.settlePlatformCall(user.getId(), UsageScene.FOLLOWUP,
                            "deepseek-flash", 8, 2, 10, false);
                } catch (Exception e) {
                    errors.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        pool.shutdown();

        assertThat(errors.get()).isZero();
        Quota q = quotaRepo.findByUserId(user.getId()).orElseThrow();
        assertThat(q.getUsedTokens()).isEqualTo(100);   // 恰好扣满，不超发
        assertThat(q.remaining()).isZero();
        assertThat(quotaService.recentUsage(user.getId(), 50)).hasSize(20);
    }
}
