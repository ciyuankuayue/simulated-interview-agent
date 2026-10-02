package com.bupt.mockinterview.web;

import com.bupt.mockinterview.billing.QuotaService;
import com.bupt.mockinterview.domain.User;
import com.bupt.mockinterview.domain.UserRole;
import com.bupt.mockinterview.domain.repository.UserRepository;
import com.bupt.mockinterview.security.JwtService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R1-3 引导注册规则（不走 Spring；@SpringBootTest 里 testuser 先建、测不出「首个账号」分支）：
 * 库为空时第一个注册者 = ADMIN 且建额度；之后注册的一律 USER。
 */
class RegisterAdminTest {

    private final UserRepository userRepo = mock(UserRepository.class);
    private final QuotaService quotaService = mock(QuotaService.class);
    private final AuthController controller = new AuthController(
            userRepo, new BCryptPasswordEncoder(), mock(JwtService.class), quotaService);

    @Test
    void firstRegisteredUser_becomesAdmin_andGetsQuota() {
        when(userRepo.existsByUsername("alice")).thenReturn(false);
        when(userRepo.count()).thenReturn(0L);
        when(userRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var resp = controller.register(new AuthController.RegisterRequest("alice", "secret123"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepo).save(captor.capture());
        assertThat(captor.getValue().getRole()).isEqualTo(UserRole.ADMIN);
        verify(quotaService).grantForNewUser(captor.getValue());
    }

    @Test
    void laterRegisteredUsers_stayNormalUser() {
        when(userRepo.existsByUsername("bob")).thenReturn(false);
        when(userRepo.count()).thenReturn(3L);
        when(userRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        controller.register(new AuthController.RegisterRequest("bob", "secret123"));

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepo).save(captor.capture());
        assertThat(captor.getValue().getRole()).isEqualTo(UserRole.USER);
    }
}
