package com.bupt.mockinterview.domain.repository;

import com.bupt.mockinterview.domain.InterviewSession;
import com.bupt.mockinterview.domain.SessionStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface InterviewSessionRepository extends JpaRepository<InterviewSession, Long> {
    List<InterviewSession> findByJdProfileId(Long jdProfileId);
    List<InterviewSession> findByStatus(SessionStatus status);
}
