package com.bupt.mockinterview.web;

import com.bupt.mockinterview.billing.QuotaService;
import com.bupt.mockinterview.domain.User;
import com.bupt.mockinterview.domain.UserRole;
import com.bupt.mockinterview.domain.repository.UserRepository;
import com.bupt.mockinterview.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R2-T0 批 2（D3）注册编排单测（不走 Spring，直接 new RegistrationService）：
 * 公开注册恒为 USER；用户名占用抛 {@link RegistrationService.UsernameTakenException}
 * （controller 映射 409）；赠额失败必须补偿删除用户。
 * 租户/Session 归属的真实验证见 RegisterQuotaTenantIntegrationTest。
 */
class RegisterAdminTest {

    private final UserRepository userRepo = mock(UserRepository.class);
    private final UserLifecycleService userLifecycle = mock(UserLifecycleService.class);
    private final QuotaService quotaService = mock(QuotaService.class);
    private final RegistrationService service = new RegistrationService(
            userRepo, userLifecycle, new BCryptPasswordEncoder(), quotaService);

    @AfterEach
    void clearTenant() {
        // 纯 Mockito 测试不经 TestTenantContextListener，service 结束会把上下文置为系统租户 0L
        TenantContext.clear();
    }

    @Test
    void newRegisteredUser_isAlwaysNormalUser_andGetsQuota() {
        when(userRepo.existsByUsername("alice")).thenReturn(false);
        when(userLifecycle.insertUser(anyString(), anyString()))
                .thenAnswer(inv -> new User(inv.getArgument(0), inv.getArgument(1)));

        User created = service.registerNewUser("alice", "secret123");

        // insertUser 的入参（answer 中原样构造返回）即 service 建好的新用户：
        // 角色恒 USER，密码已 BCrypt
        ArgumentCaptor<String> hashCaptor = ArgumentCaptor.forClass(String.class);
        verify(userLifecycle).insertUser(anyString(), hashCaptor.capture());
        assertThat(hashCaptor.getValue()).startsWith("$2a$");
        assertThat(created.getRole())
                .as("公开注册只能是 USER（ADMIN 仅 env 引导器产生）")
                .isEqualTo(UserRole.USER);
        verify(quotaService).grantForNewUser(created);
    }

    @Test
    void duplicateUsername_throwsAndHasNoSideEffects() {
        when(userRepo.existsByUsername("alice")).thenReturn(true);

        assertThatThrownBy(() -> service.registerNewUser("alice", "secret123"))
                .isInstanceOf(RegistrationService.UsernameTakenException.class);
        verify(userLifecycle, never()).insertUser(anyString(), anyString());
        verify(quotaService, never()).grantForNewUser(any());
    }

    @Test
    void grantFailure_deletesUserCompensatingly_andRethrows() {
        when(userRepo.existsByUsername("alice")).thenReturn(false);
        User persisted = new User("alice", "$2a$hash");
        when(userLifecycle.insertUser(any(), any())).thenReturn(persisted);
        doThrow(new IllegalStateException("quota down")).when(quotaService).grantForNewUser(persisted);

        assertThatThrownBy(() -> service.registerNewUser("alice", "secret123"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("quota down");
        verify(userLifecycle).deleteUser(persisted);
    }
}
