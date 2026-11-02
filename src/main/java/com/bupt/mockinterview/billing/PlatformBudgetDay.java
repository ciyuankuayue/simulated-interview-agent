package com.bupt.mockinterview.billing;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.time.LocalDate;

/** Global platform operations budget, deliberately not an owner/tenant entity. */
@Entity @Table(name = "platform_budget_day") @Getter @Setter @NoArgsConstructor
public class PlatformBudgetDay {
    @Id @Column(name = "budget_day") private LocalDate day;
    @Column(name = "reserved_micros", nullable = false) private long reservedMicros;
    @Column(name = "spent_micros", nullable = false) private long spentMicros;
    public PlatformBudgetDay(LocalDate day) { this.day = day; }
}
