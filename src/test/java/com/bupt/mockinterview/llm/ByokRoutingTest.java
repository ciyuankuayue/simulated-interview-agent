package com.bupt.mockinterview.llm;

import com.bupt.mockinterview.billing.QuotaExhaustedException;
import com.bupt.mockinterview.billing.QuotaProperties;
import com.bupt.mockinterview.billing.QuotaService;
import com.bupt.mockinterview.domain.KeyMode;
import com.bupt.mockinterview.domain.User;
import com.bupt.mockinterview.domain.repository.UserApiKeyRepository;
import com.bupt.mockinterview.llm.chat.DeepSeekChatProvider;
import com.bupt.mockinterview.llm.chat.ModelRouter;
import com.bupt.mockinterview.rag.DashscopeProperties;
import com.bupt.mockinterview.security.CurrentUserService;
import com.bupt.mockinterview.security.KeyResolver;
import com.bupt.mockinterview.security.UserKeyCipher;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
        LlmProperties props = new LlmProperties();
        // R3-1：LlmService 经 ModelRouter -> DeepSeekChatProvider（provider 在此测试不应被触达 HTTP）
        DeepSeekChatProvider deepSeek = new DeepSeekChatProvider(http, new ObjectMapper(), props);
        ModelRouter router = new ModelRouter(List.of(deepSeek), props);
        llm = new LlmService(http, new ObjectMapper(), props, router,
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
        when(keyResolver.resolveForChat(eq(u), anyString()))
                .thenReturn(new KeyResolver.ResolvedKey("sk-byok", KeyMode.BYOK));
        doThrow(QuotaExhaustedException.forMode(KeyMode.BYOK))
                .when(quotaService).ensureAvailable(7L, KeyMode.BYOK);

        assertThatThrownBy(() -> llm.followUp("system", "user"))
                .isInstanceOf(QuotaExhaustedException.class)
                .hasMessageContaining("服务积分已用完");

        verify(http, never()).newCall(any());
    }

    @Test
    void platformUserZeroBalance_throws402_beforeAnyHttpRequest() {
        User u = user("carol", 9L, KeyMode.PLATFORM);
        when(currentUser.currentUser()).thenReturn(u);
        when(keyResolver.resolveForChat(eq(u), anyString()))
                .thenReturn(new KeyResolver.ResolvedKey("sk-platform", KeyMode.PLATFORM));
        doThrow(QuotaExhaustedException.forMode(KeyMode.PLATFORM))
                .when(quotaService).ensureAvailable(9L, KeyMode.PLATFORM);

        assertThatThrownBy(() -> llm.followUp("system", "user"))
                .isInstanceOf(QuotaExhaustedException.class)
                .hasMessageContaining("内测阶段").hasMessageContaining("13481264947");

        verify(http, never()).newCall(any());
        verify(quotaService, never()).chargeForByokCall(any());
    }

    @Test
    void platformPrecheckPasses_butMissingEnvKeyGivesHumanHint() {
        User u = user("bob", 8L, KeyMode.PLATFORM);
        when(currentUser.currentUser()).thenReturn(u);
        when(keyResolver.resolveForChat(eq(u), anyString()))
                .thenReturn(new KeyResolver.ResolvedKey("", KeyMode.PLATFORM));
        // ensureAvailable 是 void，默认放行（额度充足）

        assertThatThrownBy(() -> llm.followUp("system", "user"))
                .isInstanceOf(LlmException.class)
                .hasMessageContaining("DEEPSEEK_API_KEY");

        verify(quotaService, never()).chargeForByokCall(eq(8L));
        verify(http, never()).newCall(any());
    }

    @Test
    void byokUserChoosingQwen_resolvesPlatformDashscopeKey_andNeverTouchesUserKeyStore() {
        // R3-2 拍板规则：Qwen 恒走平台 DASHSCOPE_API_KEY；BYOK 用户选它也按 PLATFORM 实扣 token，
        // 不读用户 key、不扣每次服务费（LlmService 见 mode=PLATFORM 自然走平台分支）。
        DashscopeProperties dashProps = new DashscopeProperties();
        dashProps.setApiKey("sk-dash-platform");
        UserApiKeyRepository keyRepo = mock(UserApiKeyRepository.class);
        KeyResolver realResolver = new KeyResolver(new LlmProperties(), dashProps,
                keyRepo, mock(UserKeyCipher.class));

        User u = user("dave", 10L, KeyMode.BYOK);
        KeyResolver.ResolvedKey resolved = realResolver.resolveForChat(u, "qwen");

        assertThat(resolved.apiKey()).isEqualTo("sk-dash-platform");
        assertThat(resolved.mode()).isEqualTo(KeyMode.PLATFORM);
        assertThat(resolved.byok()).isFalse();
        verifyNoInteractions(keyRepo);
    }
}
