package com.bupt.mockinterview.domain.repository;

import com.bupt.mockinterview.domain.UserApiKey;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserApiKeyRepository extends JpaRepository<UserApiKey, Long> {

    Optional<UserApiKey> findByUserId(Long userId);

    boolean existsByUserId(Long userId);

    void deleteByUserId(Long userId);
}
