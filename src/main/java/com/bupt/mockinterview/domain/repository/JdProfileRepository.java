package com.bupt.mockinterview.domain.repository;

import com.bupt.mockinterview.domain.JdProfile;
import com.bupt.mockinterview.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface JdProfileRepository extends JpaRepository<JdProfile, Long> {
    List<JdProfile> findByUser(User user);
    Optional<JdProfile> findByIdAndUserId(Long id, Long userId);
    boolean existsByIdAndUserId(Long id, Long userId);
}
