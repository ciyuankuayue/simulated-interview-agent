package com.bupt.mockinterview.domain.repository;

import com.bupt.mockinterview.domain.InterviewSession;
import com.bupt.mockinterview.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

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

    /** Serialize answer/end across application instances; always bind the explicit owner predicate. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@jakarta.persistence.QueryHint(name = "jakarta.persistence.lock.timeout", value = "0"))
    @Query("select s from InterviewSession s where s.id = :id and s.user.id = :userId")
    Optional<InterviewSession> findForUpdateByIdAndUserId(@Param("id") Long id, @Param("userId") Long userId);
}
