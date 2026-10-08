package com.bupt.mockinterview.security;

import com.bupt.mockinterview.domain.repository.JdProfileRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * R2-T0 spike #4 的窄上下文部分：strict（prod 口径）下缺租户上下文必须 fail-closed，
 * 不允许 Hibernate 静默全表/全租户访问。独立类以获得 {@code app.tenant.strict=true} 的
 * 独立 Spring 上下文（与 {@link TenantPartitionTest} 的非 strict 上下文并存）。
 */
@SpringBootTest(properties = "app.tenant.strict=true")
class TenantStrictContextTest {

    @Autowired private JdProfileRepository jdRepo;
    @Autowired private CurrentTenantResolver resolver;

    @BeforeEach
    void setUp() {
        TenantContext.clear();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void strictContext_missingTenant_resolverThrows() {
        assertThatThrownBy(resolver::resolveCurrentTenantIdentifier)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("TENANT_CONTEXT_MISSING");
    }

    @Test
    void strictContext_missingTenant_jpaQueryThrows() {
        assertThatThrownBy(() -> jdRepo.findAll())
                .hasStackTraceContaining("TENANT_CONTEXT_MISSING");
    }

    @Test
    void strictContext_explicitTenant_queryWorks() {
        TenantContext.set(CurrentTenantResolver.FALLBACK_TENANT_ID);
        // 有显式上下文（即便兜底 0L）即正常执行，证明 strict 只在「无上下文」时拒绝
        assertThat(jdRepo.findAll()).isNotNull();
    }
}
