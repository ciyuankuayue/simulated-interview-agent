package com.bupt.mockinterview.domain.repository;

import com.bupt.mockinterview.domain.ResumeVersion;
import com.bupt.mockinterview.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ResumeVersionRepository extends JpaRepository<ResumeVersion, Long> {
    /** MVP：全局找激活简历（MaterialController.activateResume 用）。 */
    List<ResumeVersion> findByIsActiveTrue();

    /** R1-1 多用户隔离。 */
    List<ResumeVersion> findByUser(User user);
    List<ResumeVersion> findByUserAndIsActiveTrue(User user);
    long countByUserId(Long userId);
    Optional<ResumeVersion> findByIdAndUserId(Long id, Long userId);
}
