package com.bupt.mockinterview.domain.repository;

import com.bupt.mockinterview.domain.ResumeVersion;
import com.bupt.mockinterview.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * R2-T0 批 6 审计：删除无显式 user 谓词的 findByIsActiveTrue（无调用方；
 * 激活流程走 findByUserAndIsActiveTrue）。
 */
public interface ResumeVersionRepository extends JpaRepository<ResumeVersion, Long> {
    /** R1-1 多用户隔离。 */
    List<ResumeVersion> findByUser(User user);
    List<ResumeVersion> findByUserAndIsActiveTrue(User user);
    long countByUserId(Long userId);
    Optional<ResumeVersion> findByIdAndUserId(Long id, Long userId);
    boolean existsByIdAndUserId(Long id, Long userId);
}
