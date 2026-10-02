package com.bupt.mockinterview.llm;

import com.bupt.mockinterview.billing.QuotaExhaustedException;
import com.bupt.mockinterview.billing.QuotaService;
import com.bupt.mockinterview.domain.KeyMode;
import com.bupt.mockinterview.domain.User;
import com.bupt.mockinterview.security.CurrentUserService;
import com.bupt.mockinterview.security.KeyResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R1-3 LlmService 收口行为单测（不走网络）：
 * 1. BYOK 用户余额不足 → 402，且发生在 HTTP 之前（OkHttp 一次都不碰，扣点不重不叠）；
 * 2. PLATFORM 用户不扣点；平台 env key 缺失 → 给人话的 LlmException（401 链路不会误扣费）。
 */
class ByokRoutingTest {

    private KeyResolver keyResolver;
    private CurrentUserService currentUser;
    private QuotaService quotaService;
    private OkHttpClient http;
    private LlmService llm;

    @BeforeEach
    void setUp() {
        keyResolver = mock(KeyResolver.class);
        currentUser = mock(CurrentUserService.class);
        quotaService = mock(QuotaService.class);
        // spy 真实 client：newBuilder 走真实实现（构造器要派生超时），同时可验证「一次请求都没发出」
        http = spy(new OkHttpClient());
        llm = new LlmService(http, new ObjectMapper(), new LlmProperties(),
                keyResolver, currentUser, quotaService);
    }

    private User byokUser() {
        User u = new User("alice", "hash");
        ReflectionTestUtils.setField(u, "id", 7L);
        u.setKeyMode(KeyMode.BYOK);
        return u;
    }

    @Test
    void byokUserOutOfQuota_throws402_beforeAnyHttpRequest() {
        User u = byokUser();
        when(currentUser.currentUser()).thenReturn(u);
        when(keyResolver.resolveForChat(u))
                .thenReturn(new KeyResolver.ResolvedKey("sk-byok", KeyMode.BYOK));
        when(quotaService.chargeForByokCall(7L)).thenReturn(false);

        assertThatThrownBy(() -> llm.followUp("system", "user"))
                .isInstanceOf(QuotaExhaustedException.class)
                .hasMessageContaining("切回平台模式");

        verify(http, never()).newCall(any());
    }

    @Test
    void platformUser_isNeverCharged_andMissingEnvKeyGivesHumanHint() {
        User u = new User("bob", "hash");
        ReflectionTestUtils.setField(u, "id", 8L);
        u.setKeyMode(KeyMode.PLATFORM);
        when(currentUser.currentUser()).thenReturn(u);
        when(keyResolver.resolveForChat(u))
                .thenReturn(new KeyResolver.ResolvedKey("", KeyMode.PLATFORM));

        assertThatThrownBy(() -> llm.followUp("system", "user"))
                .isInstanceOf(LlmException.class)
                .hasMessageContaining("DEEPSEEK_API_KEY");

        verify(quotaService, never()).chargeForByokCall(anyLong());
    }
}
