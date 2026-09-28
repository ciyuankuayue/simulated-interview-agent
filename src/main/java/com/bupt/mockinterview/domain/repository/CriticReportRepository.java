package com.bupt.mockinterview.domain.repository;

import com.bupt.mockinterview.domain.CriticReport;
import com.bupt.mockinterview.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface CriticReportRepository extends JpaRepository<CriticReport, Long> {
    CriticReport findBySessionId(Long sessionId);

    /** 批量判断哪些会话已有报告（历史列表的 hasReport 标记，避免逐行 N+1）。 */
    List<CriticReport> findBySessionIdIn(Collection<Long> sessionIds);

    /** R1-1 多用户隔离。 */
    List<CriticReport> findByUser(User user);
    Optional<CriticReport> findBySessionIdAndUser(Long sessionId, User user);
}
