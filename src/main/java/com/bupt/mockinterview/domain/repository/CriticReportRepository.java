package com.bupt.mockinterview.domain.repository;

import com.bupt.mockinterview.domain.CriticReport;
import com.bupt.mockinterview.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CriticReportRepository extends JpaRepository<CriticReport, Long> {
    /**
     * 仅用于已通过作用域校验的会话 id（如 WeakPointController 先 findByIdAndUserId 拿到的 owner 资源）：
     * R2-T0 起 @TenantId 自动分区谓词同样兜底，他人 sessionId 在非 root 上下文查返回 null。
     */
    CriticReport findBySessionId(Long sessionId);

    /** R1-1 多用户隔离。 */
    List<CriticReport> findByUser(User user);
    Optional<CriticReport> findBySessionIdAndUser(Long sessionId, User user);
}
