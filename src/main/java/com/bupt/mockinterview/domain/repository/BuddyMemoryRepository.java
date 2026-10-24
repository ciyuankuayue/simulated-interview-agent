package com.bupt.mockinterview.domain.repository;

import com.bupt.mockinterview.domain.BuddyMemory;
import org.springframework.data.repository.Repository;

import java.util.Optional;

/** Deliberately offers no unscoped id lookup or enumeration. */
public interface BuddyMemoryRepository extends Repository<BuddyMemory, Long> {
    Optional<BuddyMemory> findByUserId(Long userId);
    BuddyMemory save(BuddyMemory memory);
}
