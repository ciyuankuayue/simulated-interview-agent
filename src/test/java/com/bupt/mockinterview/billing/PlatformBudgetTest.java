package com.bupt.mockinterview.billing;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
class PlatformBudgetTest {
    @Autowired PlatformBudgetTransactions tx;
    @Autowired PlatformBudgetDayRepository days;
    @Test void reservationsBlockOtherCallersBeforeSpendingAndSettleOnce() {
        LocalDate day = LocalDate.of(2080, 1, 1);
        tx.ensureDay(day); tx.reserve("budget-once", day, 80, 100);
        assertThatThrownBy(() -> tx.reserve("budget-over", day, 21, 100)).isInstanceOf(PlatformBudgetExceededException.class);
        tx.settle("budget-once", day, 30L, 1, 1);
        tx.settle("budget-once", day, 30L, 1, 1);
        var row = days.findById(day).orElseThrow();
        assertThat(row.getReservedMicros()).isZero(); assertThat(row.getSpentMicros()).isEqualTo(30);
    }
    @Test void unknownFailureRetainsConservativeCostAndOriginalDay() {
        LocalDate day = LocalDate.of(2080, 1, 2);
        tx.ensureDay(day); tx.ensureDay(day.plusDays(1));
        tx.reserve("budget-unknown", day, 90, 100);
        tx.settle("budget-unknown", day, null, 3, 3);
        assertThat(days.findById(day).orElseThrow().getSpentMicros()).isEqualTo(90);
        assertThat(days.findById(day.plusDays(1)).orElseThrow().getSpentMicros()).isZero();
    }
    @Test void failedRetryCostIsNotAssumedFree() {
        LocalDate day = LocalDate.of(2080, 1, 4);
        tx.ensureDay(day); tx.reserve("budget-retry", day, 90, 100);
        tx.settle("budget-retry", day, 10L, 2, 3);
        assertThat(days.findById(day).orElseThrow().getSpentMicros()).isEqualTo(40);
    }
    @Test void concurrentBudgetReservationsCannotOverbook() throws Exception {
        LocalDate day = LocalDate.of(2080, 1, 5); tx.ensureDay(day);
        ExecutorService pool = Executors.newFixedThreadPool(6);
        CountDownLatch start = new CountDownLatch(1); AtomicInteger accepted = new AtomicInteger();
        java.util.List<Future<?>> tasks = new java.util.ArrayList<>();
        for (int i = 0; i < 6; i++) {
            String call = "budget-race-" + i;
            tasks.add(pool.submit(() -> {
                try { start.await(); tx.reserve(call, day, 60, 100); accepted.incrementAndGet(); }
                catch (PlatformBudgetExceededException expected) {}
                catch (InterruptedException e) { throw new RuntimeException(e); }
            }));
        }
        start.countDown();
        try { for (Future<?> task : tasks) task.get(30, TimeUnit.SECONDS); } finally { pool.shutdownNow(); }
        assertThat(accepted.get()).isEqualTo(1);
        assertThat(days.findById(day).orElseThrow().getReservedMicros()).isEqualTo(60);
    }
    @Test void invalidConfigFailsInsteadOfDisablingProtection() {
        PlatformBudgetProperties props = new PlatformBudgetProperties();
        props.setDailyCny(new BigDecimal("-1"));
        assertThatThrownBy(props::validate).isInstanceOf(IllegalStateException.class);
        props.setDailyCny(BigDecimal.ZERO); props.setMaxOutputTokens(0);
        assertThatThrownBy(props::validate).isInstanceOf(IllegalStateException.class);
    }
}
