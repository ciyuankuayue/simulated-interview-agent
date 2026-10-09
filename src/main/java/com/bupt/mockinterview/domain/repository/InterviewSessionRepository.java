package com.bupt.mockinterview.domain.repository;

import com.bupt.mockinterview.domain.InterviewSession;
import com.bupt.mockinterview.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * R2-T0 批 6 审计：删除无显式 user 谓词的 findByJdProfileId / findByStatus /
 * findAllByOrderByIdDesc（均无调用方；历史列表走 findByUserOrderByIdDesc）。
 */
public interface InterviewSessionRepository extends JpaRepository<InterviewSession, Long> {

    /** 历史会话列表（T10）：本人最近 N 场，倒序。 */
    List<InterviewSession> findByUserOrderByIdDesc(User user);
    List<InterviewSession> findByUser(User user);
    Optional<InterviewSession> findByIdAndUserId(Long id, Long userId);
}
