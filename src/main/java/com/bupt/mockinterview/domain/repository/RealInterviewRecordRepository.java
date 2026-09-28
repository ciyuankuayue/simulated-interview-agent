package com.bupt.mockinterview.domain.repository;

import com.bupt.mockinterview.domain.RealInterviewRecord;
import com.bupt.mockinterview.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RealInterviewRecordRepository extends JpaRepository<RealInterviewRecord, Long> {
    List<RealInterviewRecord> findByUser(User user);
}
