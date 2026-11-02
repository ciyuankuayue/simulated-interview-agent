package com.bupt.mockinterview.billing;

import com.bupt.mockinterview.domain.*;
import com.bupt.mockinterview.domain.repository.*;
import com.bupt.mockinterview.security.TenantContext;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import java.util.UUID;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
class UsageTariffTest {
    @Autowired QuotaService service;
    @Autowired UserRepository users;
    @Autowired QuotaRepository quotas;
    @BeforeEach void setup() { TenantContext.clear(); UsageContext.clear(); }
    @AfterEach void cleanup() { TenantContext.clear(); UsageContext.clear(); }
    private User user(long total) {
        User user = users.save(new User("tariff-" + UUID.randomUUID().toString().substring(0, 20), "hash"));
        quotas.save(new Quota(user, total)); return user;
    }
    @Test void byokChargesOnlyUsageBasedServiceFeeAndExcludesUserVendorCost() {
        User user = user(10000);
        try (UsageContext.Scope business = UsageContext.withSession(91L);
             UsageContext.Scope call = UsageContext.withCall("measured-call")) {
            service.recordByokChat(user.getId(), UsageScene.SCORE, "deepseek:deepseek-flash", 8000, 2000, 10000, false);
        }
        var row = service.recentUsage(user.getId(), 5).get(0);
        assertThat(row.getChargedTokens()).isEqualTo(1000);
        assertThat(row.getDueTokens()).isEqualTo(1000);
        assertThat(row.getPlatformCostMicros()).isZero();
        assertThat(row.getSessionId()).isEqualTo(91L);
        assertThat(row.getCallId()).isEqualTo("measured-call");
        assertThat(service.getByUser(user.getId()).remaining()).isEqualTo(9000);
        assertThat(UsageContext.sessionId()).isNull();
    }
    @Test void tinyByokUsageAndLastCallClampRecordActualDeduction() {
        User user = user(2);
        service.recordByokChat(user.getId(), UsageScene.CHAT, "deepseek:deepseek-flash", 20, 11, 31, false);
        var row = service.recentUsage(user.getId(), 1).get(0);
        assertThat(row.getDueTokens()).isEqualTo(4);
        assertThat(row.getChargedTokens()).isEqualTo(2);
        assertThat(service.getByUser(user.getId()).remaining()).isZero();
    }
    @Test void sameCallIdIsExactlyOnceIncludingConcurrentSettlers() throws Exception {
        User user = user(10000);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        java.util.List<Future<?>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < 8; i++) futures.add(pool.submit(() -> {
            try (UsageContext.Scope ignored = UsageContext.withCall("one-call")) {
                start.await();
                service.settlePlatformCall(user.getId(), UsageScene.CHAT, "deepseek:deepseek-flash", 800, 200, 1000, false);
            } catch (InterruptedException e) { throw new RuntimeException(e); }
        }));
        start.countDown();
        try { for (Future<?> future : futures) future.get(30, TimeUnit.SECONDS); }
        finally { pool.shutdownNow(); }
        assertThat(service.getByUser(user.getId()).getUsedTokens()).isEqualTo(1000);
        assertThat(service.recentUsage(user.getId(), 50)).hasSize(1);
    }
    @Test void failedCallIsVisibleButDoesNotChargeUnknownTokens() {
        User user = user(1000);
        try (UsageContext.Scope ignored = UsageContext.withCall("failed-call")) {
            UsageContext.status("FAILED");
            service.recordFailedCall(user.getId(), KeyMode.PLATFORM, UsageScene.CHAT, "deepseek:deepseek-flash");
        }
        var row = service.recentUsage(user.getId(), 1).get(0);
        assertThat(row.getStatus()).isEqualTo("FAILED");
        assertThat(row.getChargedTokens()).isZero();
        assertThat(row.getPlatformCostMicros()).isNull();
        assertThat(service.getByUser(user.getId()).remaining()).isEqualTo(1000);
    }
    @Test void conservativePricesAndUnknownModelAreExplicit() {
        assertThat(ModelCosts.estimateMicros("deepseek:deepseek-flash", 80000, 20000, 100000)).isEqualTo(320000);
        assertThat(ModelCosts.estimateMicros("qwen:qwen3.8-flash", 1000000, 0, 1000000)).isEqualTo(800000);
        assertThat(ModelCosts.estimateMicros("dashscope:qwen3.7-text-embedding-flash", 1000000, 0, 1000000)).isEqualTo(125000);
        assertThat(ModelCosts.estimateMicros("unknown", 1, 1, 2)).isNull();
        assertThatThrownBy(() -> ModelCosts.reserveMicros("unknown", 1, 4096, 3)).isInstanceOf(PlatformBudgetExceededException.class);
    }
    @Test void legacyBareModelStillSettlesButDoesNotInventKnownCost() {
        User user = user(1000);
        service.settlePlatformCall(user.getId(), UsageScene.CHAT, "deepseek-flash", 10, 5, 15, false);
        var row = service.recentUsage(user.getId(), 1).get(0);
        assertThat(row.getChargedTokens()).isEqualTo(15);
        assertThat(row.getPlatformCostMicros()).isNull();
    }
}
