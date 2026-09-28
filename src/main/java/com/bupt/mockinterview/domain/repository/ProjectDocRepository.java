package com.bupt.mockinterview.domain.repository;

import com.bupt.mockinterview.domain.ProjectDoc;
import com.bupt.mockinterview.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ProjectDocRepository extends JpaRepository<ProjectDoc, Long> {
    List<ProjectDoc> findByUser(User user);
    Optional<ProjectDoc> findByIdAndUserId(Long id, Long userId);
}
