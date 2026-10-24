package com.bupt.mockinterview.memory;

import com.bupt.mockinterview.domain.repository.BuddyMemoryRepository;
import com.bupt.mockinterview.security.ForbiddenException;
import com.bupt.mockinterview.security.TenantContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class BuddyMemoryServiceTest {
    private final BuddyMemoryRepository repository = mock(BuddyMemoryRepository.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private final BuddyMemoryService service = new BuddyMemoryService(repository, new ObjectMapper(),
            mock(EntityManager.class), transactions);

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void nullOwnerNeverReadsOrWritesPersistentMemory() {
        assertThat(service.context(null, "coach")).isEqualTo(new BuddyMemoryService.Context(false, 0, ""));
        service.applyCandidates(null, "companion", 1, List.of(new BuddyMemoryService.Candidate("target_role", "后端", "想做后端")));
        verifyNoInteractions(repository, transactions);
    }

    @Test
    void tenantMustBeSetBeforeOpeningAnyTransaction() {
        assertThatThrownBy(() -> service.context(41L, "coach")).isInstanceOf(ForbiddenException.class);
        TenantContext.set(42L);
        assertThatThrownBy(() -> service.setEnabled(41L, true)).isInstanceOf(ForbiddenException.class);
        TenantContext.runAsRoot(() -> assertThatThrownBy(() -> service.view(41L)).isInstanceOf(ForbiddenException.class));
        verifyNoInteractions(repository, transactions);
    }

    @Test
    void invalidKeysAndValuesAreRejectedBeforeOpeningTransactions() {
        assertThatThrownBy(() -> service.edit(41L, "mood", "开心")).isInstanceOf(BuddyMemoryValidationException.class);
        assertThatThrownBy(() -> service.edit(41L, "target_role", " ")).isInstanceOf(BuddyMemoryValidationException.class);
        assertThatThrownBy(() -> service.edit(41L, "target_role", "x".repeat(301))).isInstanceOf(BuddyMemoryValidationException.class);
        assertThatThrownBy(() -> service.edit(41L, "target_role", "后端\n开发")).isInstanceOf(BuddyMemoryValidationException.class);
        assertThatThrownBy(() -> service.context(41L, "unknown")).isInstanceOf(BuddyMemoryValidationException.class);
        verifyNoInteractions(repository, transactions);
    }
}
