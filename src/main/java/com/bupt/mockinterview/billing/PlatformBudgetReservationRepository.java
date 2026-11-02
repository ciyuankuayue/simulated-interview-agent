package com.bupt.mockinterview.billing;

import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import java.util.Optional;

public interface PlatformBudgetReservationRepository extends JpaRepository<PlatformBudgetReservation, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from PlatformBudgetReservation r where r.callId = :callId")
    Optional<PlatformBudgetReservation> lockCall(@Param("callId") String callId);
}
