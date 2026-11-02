package com.bupt.mockinterview.billing;

import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import java.time.LocalDate;
import java.util.Optional;

public interface PlatformBudgetDayRepository extends JpaRepository<PlatformBudgetDay, LocalDate> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from PlatformBudgetDay d where d.day = :day")
    Optional<PlatformBudgetDay> lockDay(@Param("day") LocalDate day);
}
