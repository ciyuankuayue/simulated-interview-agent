package com.bupt.mockinterview.agent;

import org.junit.jupiter.api.Test;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class InterviewRequestGuardTest {
    @Test void overlappingWritesAreRejectedBeforeWorkAndGuardReleasesAfterCompletion() throws Exception {
        var guard = new InterviewRequestGuard();
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var calls = new AtomicInteger();
        var executor = Executors.newSingleThreadExecutor();
        try {
            var running = executor.submit(() -> guard.execute(1L, 2L, () -> {
                calls.incrementAndGet(); entered.countDown();
                try { assertTrue(release.await(5, TimeUnit.SECONDS)); } catch (InterruptedException e) { throw new RuntimeException(e); }
                return "done";
            }));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertThrows(IllegalStateException.class, () -> guard.execute(1L, 2L, () -> { calls.incrementAndGet(); return "duplicate"; }));
            assertEquals("independent", guard.execute(2L, 2L, () -> "independent"));
            release.countDown(); assertEquals("done", running.get(5, TimeUnit.SECONDS));
            assertEquals("retry", guard.execute(1L, 2L, () -> "retry"));
            assertEquals(1, calls.get());
        } finally { release.countDown(); executor.shutdownNow(); }
    }
    @Test void exceptionsDoNotLeaveAnInFlightSession() {
        var guard = new InterviewRequestGuard();
        assertThrows(RuntimeException.class, () -> guard.execute(1L, 2L, () -> { throw new RuntimeException(); }));
        assertEquals("recovered", guard.execute(1L, 2L, () -> "recovered"));
    }
}
