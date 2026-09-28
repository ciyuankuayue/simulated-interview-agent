package com.bupt.mockinterview.domain.repository;

import com.bupt.mockinterview.domain.InterviewSession;
import com.bupt.mockinterview.domain.SessionStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface InterviewSessionRepository extends JpaRepository<InterviewSession, Long> {
    List<InterviewSession> findByJdProfileId(Long jdProfileId);
    List<InterviewSession> findByStatus(SessionStatus status);

    /** 历史会话列表（T10）：最近 N 场，倒序。Pageable 限条数，不引分页参数。 */
    List<InterviewSession> findAllByOrderByIdDesc(Pageable page);
}
