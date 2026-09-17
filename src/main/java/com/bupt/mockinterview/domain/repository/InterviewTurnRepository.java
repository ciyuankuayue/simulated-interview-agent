package com.bupt.mockinterview.domain.repository;

import com.bupt.mockinterview.domain.InterviewTurn;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface InterviewTurnRepository extends JpaRepository<InterviewTurn, Long> {
    List<InterviewTurn> findBySessionIdOrderBySeqAsc(Long sessionId);
}
