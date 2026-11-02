package com.bupt.mockinterview.llm;

import com.bupt.mockinterview.billing.QuotaExhaustedException;
import com.bupt.mockinterview.billing.QuotaProperties;
import com.bupt.mockinterview.billing.QuotaService;
import com.bupt.mockinterview.billing.UsageScene;
import com.bupt.mockinterview.domain.ChatProviderChoice;
import com.bupt.mockinterview.domain.KeyMode;
import com.bupt.mockinterview.domain.User;
import com.bupt.mockinterview.llm.chat.ChatProvider;
import com.bupt.mockinterview.llm.chat.ChatResponse;
import com.bupt.mockinterview.llm.chat.ChatStreamException;
import com.bupt.mockinterview.llm.chat.ModelRole;
import com.bupt.mockinterview.llm.chat.ModelRouter;
import com.bupt.mockinterview.security.CurrentUserService;
import com.bupt.mockinterview.security.KeyResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LlmServiceStreamTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final ChatProvider deepseek = mock(ChatProvider.class);
    private final ChatProvider qwen = mock(ChatProvider.class);
    private final CurrentUserService currentUser = mock(CurrentUserService.class);
    private final KeyResolver keys = mock(KeyResolver.class);
    private final QuotaService quota = mock(QuotaService.class);
    private final QuotaProperties quotaProps = new QuotaProperties();
    private final List<Map<String, Object>> messages = List.of(LlmService.userMsg("讲讲索引"));
    private LlmService llm;
    private User user;

    @BeforeEach
    void setUp() {
        when(deepseek.id()).thenReturn("deepseek");
        when(qwen.id()).thenReturn("qwen");
        LlmProperties props = new LlmProperties();
        llm = new LlmService(new OkHttpClient(), mapper, props,
                new ModelRouter(List.of(deepseek, qwen), props), keys, currentUser, quota, quotaProps);
        user = new User("stream-test", "hash");
        ReflectionTestUtils.setField(user, "id", 7L);
        when(currentUser.currentUser()).thenReturn(user);
        when(keys.resolveForChat(user, "deepseek"))
                .thenReturn(new KeyResolver.ResolvedKey("offline-platform", KeyMode.PLATFORM));
    }

    @Test
    void actualStreamUsesUnifiedRoutingAndBillsTailUsageOnce() throws Exception {
        ChatResponse response = new ChatResponse("索引像目录", null,
                mapper.readTree("{\"prompt_tokens\":11,\"completion_tokens\":7,\"total_tokens\":18}"));
        when(deepseek.stream(any(), anyString(), any())).thenAnswer(invocation -> {
            Consumer<String> sink = invocation.getArgument(2);
            sink.accept("索引像"); sink.accept("目录");
            return response;
        });
        List<String> deltas = new ArrayList<>();

        LlmReply reply = llm.streamChat(ModelRole.FAST, false, messages, deltas::add);

        assertThat(deltas).containsExactly("索引像", "目录");
        assertThat(reply.content()).isEqualTo("索引像目录");
        assertThat(reply.usage()).isEqualTo(new TokenUsage(11, 7, 18, false));
        verify(keys).resolveForChat(user, "deepseek");
        verify(quota).ensureAvailable(7L, KeyMode.PLATFORM);
        verify(quota, times(1)).settlePlatformCall(7L, UsageScene.CHAT, "deepseek:deepseek-flash", 11, 7, 18, false);
        verify(deepseek, never()).chat(any(), anyString());
    }

    @Test
    void partialFailureStillSettlesMeasuredUsageOnceAndPropagates() throws Exception {
        ChatResponse partial = new ChatResponse("未完", null,
                mapper.readTree("{\"prompt_tokens\":10,\"completion_tokens\":2,\"total_tokens\":12}"));
        ChatStreamException failure = new ChatStreamException("断流", new IOException("cut"), partial);
        when(deepseek.stream(any(), anyString(), any())).thenThrow(failure);

        assertThatThrownBy(() -> llm.streamChat(ModelRole.FAST, false, messages, text -> {})).isSameAs(failure);

        verify(quota, times(1)).settlePlatformCall(7L, UsageScene.CHAT, "deepseek:deepseek-flash", 10, 2, 12, false);
        verify(deepseek, times(1)).stream(any(), anyString(), any());
    }

    @Test
    void partialFailureWithoutUsageUsesExistingEstimateFlag() {
        when(deepseek.stream(any(), anyString(), any())).thenThrow(
                new ChatStreamException("断流", new IOException("cut"), new ChatResponse("未完", null, null)));

        assertThatThrownBy(() -> llm.streamChat(ModelRole.FAST, false, messages, text -> {}))
                .isInstanceOf(ChatStreamException.class);

        verify(quota).settlePlatformCall(7L, UsageScene.CHAT, "deepseek:deepseek-flash", 0, 0,
                "讲讲索引".length() + "未完".length(), true);
    }

    @Test
    void disabledEstimateLeavesUnknownUsageUncharged() {
        quotaProps.setUsageEstimateEnabled(false);
        when(deepseek.stream(any(), anyString(), any())).thenThrow(
                new ChatStreamException("断流", new IOException("cut"), new ChatResponse("未完", null, null)));

        assertThatThrownBy(() -> llm.streamChat(ModelRole.FAST, false, messages, text -> {}))
                .isInstanceOf(ChatStreamException.class);

        verify(quota, never()).settlePlatformCall(anyLong(), any(), anyString(), anyLong(), anyLong(), anyLong(), anyBoolean());
    }

    @Test
    void failureBeforeAnyModelOutputDoesNotEstimatePromptOnlyCharge() {
        when(deepseek.stream(any(), anyString(), any())).thenThrow(
                new ChatStreamException("连接失败", new IOException("connect"), null));

        assertThatThrownBy(() -> llm.streamChat(ModelRole.FAST, false, messages, text -> {}))
                .isInstanceOf(ChatStreamException.class);

        verify(quota, never()).settlePlatformCall(anyLong(), any(), anyString(), anyLong(), anyLong(), anyLong(), anyBoolean());
    }

    @Test
    void byokPrechecksThenChargesPartialMeasuredUsage() throws Exception {
        when(keys.resolveForChat(user, "deepseek")).thenReturn(new KeyResolver.ResolvedKey("offline-byok", KeyMode.BYOK));
        when(deepseek.stream(any(), anyString(), any())).thenThrow(new ChatStreamException("断流", null,
                new ChatResponse("未完", null, mapper.readTree("{\"total_tokens\":12}"))));

        assertThatThrownBy(() -> llm.streamChat(ModelRole.FAST, false, messages, text -> {}))
                .isInstanceOf(ChatStreamException.class);

        verify(quota, never()).chargeForByokCall(7L);
        verify(quota).ensureAvailable(7L, KeyMode.BYOK);
        verify(quota, times(1)).recordByokChat(7L, UsageScene.CHAT, "deepseek:deepseek-flash", 0, 0, 12, false);
        verify(quota, never()).settlePlatformCall(anyLong(), any(), anyString(), anyLong(), anyLong(), anyLong(), anyBoolean());
    }

    @Test
    void byokUserSelectingQwenUsesResolvedPlatformKeyAndNoServiceFee() throws Exception {
        user.setKeyMode(KeyMode.BYOK);
        user.setChatProvider(ChatProviderChoice.QWEN);
        when(keys.resolveForChat(user, "qwen")).thenReturn(new KeyResolver.ResolvedKey("offline-qwen-env", KeyMode.PLATFORM));
        when(qwen.stream(any(), eq("offline-qwen-env"), any())).thenReturn(new ChatResponse("回答", null,
                mapper.readTree("{\"total_tokens\":9}")));

        llm.streamChat(ModelRole.FAST, false, messages, text -> {});

        verify(quota).ensureAvailable(7L, KeyMode.PLATFORM);
        verify(quota).settlePlatformCall(7L, UsageScene.CHAT, "qwen:qwen3.8-flash", 0, 0, 9, false);
        verify(quota, never()).chargeForByokCall(anyLong());
        verify(deepseek, never()).stream(any(), anyString(), any());
    }

    @Test
    void exhaustedQuotaPreventsAllProviderRequests() {
        doThrow(QuotaExhaustedException.forMode(KeyMode.PLATFORM)).when(quota).ensureAvailable(7L, KeyMode.PLATFORM);

        assertThatThrownBy(() -> llm.streamChat(ModelRole.FAST, false, messages, text -> {}))
                .isInstanceOf(QuotaExhaustedException.class);

        verify(deepseek, never()).stream(any(), anyString(), any());
    }

    @Test
    void budgetDenialPreventsProviderRequestBeforeAnyTokenIsSpent() {
        doThrow(new com.bupt.mockinterview.billing.PlatformBudgetExceededException("预算已满"))
                .when(quota).reservePlatformCall(anyString(), anyString(), anyLong(), anyInt(), anyInt());
        assertThatThrownBy(() -> llm.streamChat(ModelRole.FAST, false, messages, text -> {}))
                .isInstanceOf(com.bupt.mockinterview.billing.PlatformBudgetExceededException.class);
        verify(deepseek, never()).stream(any(), anyString(), any());
    }
}
