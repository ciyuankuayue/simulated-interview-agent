package com.bupt.mockinterview.billing;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.*;
import java.time.LocalDate;

// Translate direct EntityManager constraint failures just like Spring Data repositories.
// The first-day concurrent INSERT loser must reach the service as DataIntegrityViolationException.
@Repository
public class PlatformBudgetTransactions {
    @jakarta.persistence.PersistenceContext private jakarta.persistence.EntityManager entityManager;
    private final PlatformBudgetDayRepository days;
    private final PlatformBudgetReservationRepository reservations;
    public PlatformBudgetTransactions(PlatformBudgetDayRepository days, PlatformBudgetReservationRepository reservations) {
        this.days = days; this.reservations = reservations;
    }
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void ensureDay(LocalDate day) {
        if (!days.existsById(day)) {
            // Assigned-id repository.save uses merge and could reset a row created by another instance.
            // Force INSERT; a losing creator rolls back on uniqueness instead of overwriting its counters.
            entityManager.persist(new PlatformBudgetDay(day));
            entityManager.flush();
        }
    }
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void reserve(String callId, LocalDate day, long micros, long limit) {
        PlatformBudgetDay row = days.lockDay(day).orElseThrow();
        if (reservations.lockCall(callId).isPresent()) throw new IllegalStateException("重复的平台调用编号");
        if (micros > limit - row.getSpentMicros() - row.getReservedMicros()) {
            throw new PlatformBudgetExceededException("今日平台体验额度已达预算，请稍后再试或联系开发者");
        }
        row.setReservedMicros(Math.addExact(row.getReservedMicros(), micros));
        reservations.save(new PlatformBudgetReservation(callId, day, micros));
    }
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void settle(String callId, LocalDate day, Long knownCost, int attempts, int maxAttempts) {
        // Same lock order as reserve prevents reservation/day deadlocks.
        PlatformBudgetDay row = days.lockDay(day).orElseThrow();
        PlatformBudgetReservation reservation = reservations.lockCall(callId).orElseThrow();
        if (reservation.isSettled()) return;
        long reserve = reservation.getReservedMicros();
        // Unknown failed attempts are conservatively retained as spent. A timeout is not proof of zero charge.
        long unknownAttempts = Math.max(0, attempts - 1);
        long charge = knownCost == null ? reserve : Math.addExact(Math.max(0, knownCost),
                Math.multiplyExact((reserve + maxAttempts - 1) / maxAttempts, unknownAttempts));
        row.setReservedMicros(row.getReservedMicros() - reserve);
        row.setSpentMicros(Math.addExact(row.getSpentMicros(), charge));
        reservation.setSettled(true);
    }
}
