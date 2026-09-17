package com.bupt.mockinterview.domain.repository;

import com.bupt.mockinterview.domain.WeakPoint;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface WeakPointRepository extends JpaRepository<WeakPoint, Long> {
    List<WeakPoint> findByJdProfileId(Long jdProfileId);
    List<WeakPoint> findByNextReviewAtLessThanEqual(Instant now);
}
