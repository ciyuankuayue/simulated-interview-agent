package com.bupt.mockinterview.domain.repository;

import com.bupt.mockinterview.domain.ResumeVersion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ResumeVersionRepository extends JpaRepository<ResumeVersion, Long> {
    List<ResumeVersion> findByIsActiveTrue();
}
