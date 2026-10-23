package com.bupt.mockinterview.llm;

import com.bupt.mockinterview.billing.QuotaProperties;
import com.bupt.mockinterview.billing.QuotaService;
import com.bupt.mockinterview.billing.UsageScene;
import com.bupt.mockinterview.domain.KeyMode;
import com.bupt.mockinterview.domain.User;
import com.bupt.mockinterview.llm.chat.ChatProvider;
import com.bupt.mockinterview.llm.chat.ChatRequest;
import com.bupt.mockinterview.llm.chat.ChatResponse;
import com.bupt.mockinterview.llm.chat.ModelRole;
import com.bupt.mockinterview.llm.chat.ModelRouter;
import com.bupt.mockinterview.security.CurrentUserService;
import com.bupt.mockinterview.security.KeyResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LlmServiceToolCallsTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final ChatProvider provider = mock(ChatProvider.class);
    private final CurrentUserService currentUser = mock(CurrentUserService.class);
    private final KeyResolver keys = mock(KeyResolver.class);
    private final QuotaService quota = mock(QuotaService.class);
    private final QuotaProperties quotaProps = new QuotaProperties();
    private LlmService llm;
    private User user;

    @BeforeEach
    void setUp() {
        when(provider.id()).thenReturn("deepseek");
        LlmProperties props = new LlmProperties();
        llm = new LlmService(new OkHttpClient(), mapper, props,
                new ModelRouter(List.of(provider), props), keys, currentUser, quota, quotaProps);
        user = new User("tool-test", "hash");
        ReflectionTestUtils.setField(user, "id", 7L);
        when(currentUser.currentUser()).thenReturn(user);
        when(keys.resolveForChat(user, "deepseek"))
                .thenReturn(new KeyResolver.ResolvedKey("offline-platform", KeyMode.PLATFORM));
    }

    @Test
    void toolOnlyReply_isReturnedAndChargedUsingProviderUsage() throws Exception {
        List<ToolCall> calls = List.of(new ToolCall("call-1", "search_materials", "{\"query\":\"项目\"}"));
        when(provider.chat(any(), anyString())).thenReturn(new ChatResponse("", "需要证据",
                mapper.readTree("{\"prompt_tokens\":11,\"completion_tokens\":9,\"total_tokens\":20}"), calls));
        List<Map<String, Object>> messages = List.of(LlmService.userMsg("追问前先查证"));
        List<Map<String, Object>> tools = List.of(Map.of("type", "function",
                "function", Map.of("name", "search_materials")));

        LlmReply reply = llm.chat(ModelRole.REASONER, true, messages, tools, false, 4096, UsageScene.FOLLOWUP);

        assertThat(reply.content()).isEmpty();
        assertThat(reply.reasoningContent()).isEqualTo("需要证据");
        assertThat(reply.toolCalls()).isEqualTo(calls);
        assertThat(reply.usage()).isEqualTo(new TokenUsage(11, 9, 20, false));
        ArgumentCaptor<ChatRequest> request = ArgumentCaptor.forClass(ChatRequest.class);
        verify(provider).chat(request.capture(), eq("offline-platform"));
        assertThat(request.getValue().messages()).isEqualTo(messages);
        assertThat(request.getValue().tools()).isEqualTo(tools);
        verify(quota).ensureAvailable(7L, KeyMode.PLATFORM);
        verify(quota).settlePlatformCall(7L, UsageScene.FOLLOWUP, "deepseek:deepseek-flash", 11, 9, 20, false);
    }

    @Test
    void missingUsage_estimatesInputAndOutputToolNamesAndArguments() {
        List<ToolCall> calls = List.of(new ToolCall("call-2", "read_history", "{}"));
        when(provider.chat(any(), anyString())).thenReturn(new ChatResponse(null, null, null, calls));
        List<Map<String, Object>> messages = List.of(
                Map.of("role", "assistant", "content", "", "reasoning_content", "需查证",
                        "tool_calls", List.of(Map.of("function",
                                Map.of("name", "search_materials", "arguments", "{\"query\":\"索引\"}")))),
                Map.of("role", "tool", "tool_call_id", "call-1", "content", "检索到资料"));
        long expected = "需查证".length() + "search_materials".length() + "{\"query\":\"索引\"}".length()
                + "检索到资料".length() + "read_history".length() + "{}".length();

        LlmReply reply = llm.chat(ModelRole.FAST, false, messages, null, false, 4096);

        assertThat(reply.usage()).isEqualTo(new TokenUsage(0, 0, expected, true));
        verify(quota).settlePlatformCall(7L, UsageScene.CHAT, "deepseek:deepseek-flash", 0, 0, expected, true);
    }

    @Test
    void byokToolReply_keepsPerCallFeeAndUsageLogSemantics() {
        when(keys.resolveForChat(user, "deepseek"))
                .thenReturn(new KeyResolver.ResolvedKey("offline-byok", KeyMode.BYOK));
        when(quota.chargeForByokCall(7L)).thenReturn(true);
        when(provider.chat(any(), anyString())).thenReturn(new ChatResponse("", null, null,
                List.of(new ToolCall("call-1", "read_history", "{}"))));

        LlmReply reply = llm.chat(ModelRole.FAST, false, List.of(), null, false, 4096);

        verify(quota).chargeForByokCall(7L);
        verify(quota).recordByokChat(7L, UsageScene.CHAT, "deepseek:deepseek-flash", 0, 0,
                reply.usage().totalTokens(), true);
        verify(quota, never()).settlePlatformCall(anyLong(), any(), anyString(), anyLong(), anyLong(), anyLong(), anyBoolean());
    }

    @Test
    void emptyReply_withoutCallsIsRejectedAfterUsageSettlement() throws Exception {
        when(provider.chat(any(), anyString())).thenReturn(new ChatResponse(" ", "思考后未输出",
                mapper.readTree("{\"total_tokens\":10}")));

        assertThatThrownBy(() -> llm.chat(ModelRole.FAST, false, List.of(), null, false, 4096))
                .isInstanceOf(LlmException.class).hasMessageContaining("均为空");
        verify(quota).settlePlatformCall(7L, UsageScene.CHAT, "deepseek:deepseek-flash", 0, 0, 10, false);
    }

    @Test
    void legacyConstructorsAndNullLists_normalizeToEmpty() {
        assertThat(new ChatResponse("answer", null, null).toolCalls()).isEmpty();
        assertThat(new ChatResponse("answer", null, null, null).toolCalls()).isEmpty();
        assertThat(new LlmReply("answer", null).toolCalls()).isEmpty();
        assertThat(new LlmReply("answer", null, null).toolCalls()).isEmpty();
        assertThat(new LlmReply("answer", null, null, null).toolCalls()).isEmpty();
    }
}
