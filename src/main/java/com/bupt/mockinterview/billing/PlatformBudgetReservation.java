package com.bupt.mockinterview.billing;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.time.Instant;
import java.time.LocalDate;

/** Opaque call identifiers only. Crash reservations remain held for safe manual reconciliation. */
@Entity @Table(name = "platform_budget_reservation") @Getter @Setter @NoArgsConstructor
public class PlatformBudgetReservation {
    @Id @Column(name = "call_id", length = 64) private String callId;
    @Column(name = "budget_day", nullable = false) private LocalDate day;
    @Column(name = "reserved_micros", nullable = false) private long reservedMicros;
    @Column(name = "settled", nullable = false) private boolean settled;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    public PlatformBudgetReservation(String callId, LocalDate day, long micros) {
        this.callId = callId; this.day = day; this.reservedMicros = micros; this.createdAt = Instant.now();
    }
}
