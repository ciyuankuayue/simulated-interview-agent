package com.bupt.mockinterview.domain.repository;

import com.bupt.mockinterview.domain.CriticReport;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CriticReportRepository extends JpaRepository<CriticReport, Long> {
    CriticReport findBySessionId(Long sessionId);
}
