package com.bupt.mockinterview.llm;

import com.bupt.mockinterview.billing.QuotaExhaustedException;
import com.bupt.mockinterview.billing.QuotaProperties;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * LlmService 计费收口行为单测（不走网络）：
 * 1. BYOK 余额不足 → 402，且发生在 HTTP 之前（OkHttp 一次都不碰，服务费不重不叠）；
 * 2. PLATFORM 预检（ensureAvailable）拦 0 额度 → 402，同样在 HTTP 之前；
 * 3. 平台 env key 缺失 → 人话 LlmException（预检先过、requireApiKey 拦，不误烧请求）。
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
                keyResolver, currentUser, quotaService, new QuotaProperties());
    }

    private User user(String name, long id, KeyMode mode) {
        User u = new User(name, "hash");
        ReflectionTestUtils.setField(u, "id", id);
        u.setKeyMode(mode);
        return u;
    }

    @Test
    void byokUserOutOfQuota_throws402_beforeAnyHttpRequest() {
        User u = user("alice", 7L, KeyMode.BYOK);
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
    void platformUserZeroBalance_throws402_beforeAnyHttpRequest() {
        User u = user("carol", 9L, KeyMode.PLATFORM);
        when(currentUser.currentUser()).thenReturn(u);
        when(keyResolver.resolveForChat(u))
                .thenReturn(new KeyResolver.ResolvedKey("sk-platform", KeyMode.PLATFORM));
        doThrow(QuotaExhaustedException.forMode(KeyMode.PLATFORM))
                .when(quotaService).ensureAvailable(9L, KeyMode.PLATFORM);

        assertThatThrownBy(() -> llm.followUp("system", "user"))
                .isInstanceOf(QuotaExhaustedException.class)
                .hasMessageContaining("联系管理员充值");

        verify(http, never()).newCall(any());
        verify(quotaService, never()).chargeForByokCall(any());
    }

    @Test
    void platformPrecheckPasses_butMissingEnvKeyGivesHumanHint() {
        User u = user("bob", 8L, KeyMode.PLATFORM);
        when(currentUser.currentUser()).thenReturn(u);
        when(keyResolver.resolveForChat(u))
                .thenReturn(new KeyResolver.ResolvedKey("", KeyMode.PLATFORM));
        // ensureAvailable 是 void，默认放行（额度充足）

        assertThatThrownBy(() -> llm.followUp("system", "user"))
                .isInstanceOf(LlmException.class)
                .hasMessageContaining("DEEPSEEK_API_KEY");

        verify(quotaService, never()).chargeForByokCall(eq(8L));
        verify(http, never()).newCall(any());
    }
}
