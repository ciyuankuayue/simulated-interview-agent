package com.bupt.mockinterview.billing;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;

@Service
public class PlatformBudgetService {
    public record Reservation(String callId, LocalDate day, int maxAttempts) {}
    private final PlatformBudgetTransactions transactions;
    private final PlatformBudgetProperties props;
    public PlatformBudgetService(PlatformBudgetTransactions transactions, PlatformBudgetProperties props) {
        this.transactions = transactions; this.props = props;
    }
    public int capOutput(int requested) {
        return Math.max(4096, Math.min(requested <= 0 ? props.getMaxOutputTokens() : requested, props.getMaxOutputTokens()));
    }
    public Reservation reserve(String callId, String model, long inputTokenBound, int outputBound, int maxAttempts) {
        LocalDate day = LocalDate.now(ZoneId.of("Asia/Shanghai"));
        long bound = ModelCosts.reserveMicros(model, Math.max(1, inputTokenBound), outputBound, maxAttempts);
        try { transactions.ensureDay(day); } catch (DataIntegrityViolationException concurrentDayCreation) {
            // Another instance committed today's row. The next locked transaction resolves it.
        }
        long limit = props.getDailyCny().movePointRight(6).setScale(0, RoundingMode.DOWN).longValueExact();
        transactions.reserve(callId, day, bound, limit);
        return new Reservation(callId, day, maxAttempts);
    }
    public void settle(Reservation reservation, Long costMicros, int attempts) {
        if (reservation != null) transactions.settle(reservation.callId(), reservation.day(), costMicros,
                attempts, reservation.maxAttempts());
    }
}
