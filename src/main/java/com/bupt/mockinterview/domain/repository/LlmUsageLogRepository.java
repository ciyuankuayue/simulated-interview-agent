package com.bupt.mockinterview.domain.repository;

import com.bupt.mockinterview.domain.LlmUsageLog;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface LlmUsageLogRepository extends JpaRepository<LlmUsageLog, Long> {

    /** 本人最近用量流水（id 倒序≈时间倒序，省掉 created_at 排序开销）。 */
    List<LlmUsageLog> findByUserIdOrderByIdDesc(Long userId, Pageable pageable);
}
