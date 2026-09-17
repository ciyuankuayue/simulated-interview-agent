package com.bupt.mockinterview.domain.repository;

import com.bupt.mockinterview.domain.RealInterviewRecord;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RealInterviewRecordRepository extends JpaRepository<RealInterviewRecord, Long> {
}
