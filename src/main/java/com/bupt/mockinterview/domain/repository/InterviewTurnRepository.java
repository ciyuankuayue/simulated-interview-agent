package com.bupt.mockinterview.domain.repository;

import com.bupt.mockinterview.domain.InterviewTurn;
import com.bupt.mockinterview.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface InterviewTurnRepository extends JpaRepository<InterviewTurn, Long> {
    List<InterviewTurn> findBySessionIdOrderBySeqAsc(Long sessionId);
    List<InterviewTurn> findByUser(User user);
}
