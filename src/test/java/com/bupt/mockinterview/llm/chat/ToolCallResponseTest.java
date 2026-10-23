package com.bupt.mockinterview.llm.chat;

import com.bupt.mockinterview.llm.LlmException;
import com.bupt.mockinterview.llm.LlmProperties;
import com.bupt.mockinterview.llm.ToolCall;
import com.bupt.mockinterview.rag.DashscopeProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.OkHttpClient;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 两个厂商的工具响应都只做离线映射，不发网络请求。 */
class ToolCallResponseTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private ChatResponse response(String provider, JsonNode root) {
        return "deepseek".equals(provider)
                ? new DeepSeekChatProvider(new OkHttpClient(), mapper, new LlmProperties()).toResponse(root)
                : new QwenChatProvider(new OkHttpClient(), mapper, new DashscopeProperties()).toResponse(root);
    }

    private ObjectNode root(ObjectNode message) {
        ObjectNode root = mapper.createObjectNode();
        root.putArray("choices").addObject().set("message", message);
        root.putObject("usage").put("total_tokens", 17);
        return root;
    }

    private ObjectNode call(String id, String name, String arguments) {
        ObjectNode call = mapper.createObjectNode().put("id", id).put("type", "function");
        call.putObject("function").put("name", name).put("arguments", arguments);
        return call;
    }

    @ParameterizedTest
    @ValueSource(strings = {"deepseek", "qwen"})
    void toolOnlyResponse_preservesCallsReasoningAndUsage(String provider) {
        ObjectNode message = mapper.createObjectNode().putNull("content").put("reasoning_content", "需核实资料");
        message.putArray("tool_calls")
                .add(call("call-1", "search_materials", "{\"query\":\"订单指标\"}"))
                .add(call("call-2", "read_history", "{}"));

        ChatResponse response = response(provider, root(message));

        assertThat(response.content()).isEmpty();
        assertThat(response.reasoning()).isEqualTo("需核实资料");
        assertThat(response.toolCalls()).containsExactly(
                new ToolCall("call-1", "search_materials", "{\"query\":\"订单指标\"}"),
                new ToolCall("call-2", "read_history", "{}"));
        assertThat(response.usage().path("total_tokens").asLong()).isEqualTo(17L);
    }

    @ParameterizedTest
    @ValueSource(strings = {"deepseek", "qwen"})
    void textResponse_stillWorksWithoutToolCalls(String provider) {
        ChatResponse response = response(provider, root(mapper.createObjectNode().put("content", "请解释测量方法。")));
        assertThat(response.content()).isEqualTo("请解释测量方法。");
        assertThat(response.toolCalls()).isEmpty();
        assertThat(response.reasoning()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"deepseek", "qwen"})
    void emptyOrMissingMessage_isRejected(String provider) {
        assertThatThrownBy(() -> response(provider, null)).isInstanceOf(LlmException.class);
        assertThatThrownBy(() -> response(provider, mapper.createObjectNode())).isInstanceOf(LlmException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"deepseek", "qwen"})
    void malformedToolCalls_areRejectedEvenWithText(String provider) {
        List<ObjectNode> malformed = List.of(
                call("", "search_materials", "{}"),
                call("call-1", "", "{}"),
                call("call-1", "search_materials", "{broken"),
                call("call-1", "search_materials", "[]"),
                call("call-1", "search_materials", "{} runtime-instructions"),
                call("call-1", "search_materials", "{}").put("type", "unknown"));
        for (ObjectNode call : malformed) {
            ObjectNode message = mapper.createObjectNode().put("content", "不能靠正文掩盖错误调用");
            message.putArray("tool_calls").add(call);
            assertThatThrownBy(() -> response(provider, root(message)))
                    .as("错误调用：%s", call).isInstanceOf(LlmException.class);
        }

        ObjectNode nonArray = mapper.createObjectNode().put("tool_calls", "not-array");
        assertThatThrownBy(() -> response(provider, root(nonArray))).isInstanceOf(LlmException.class);

        ObjectNode objectArgs = call("call-1", "search_materials", "{}");
        ((ObjectNode) objectArgs.path("function")).putObject("arguments");
        ObjectNode invalidType = mapper.createObjectNode();
        invalidType.putArray("tool_calls").add(objectArgs);
        assertThatThrownBy(() -> response(provider, root(invalidType))).isInstanceOf(LlmException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"deepseek", "qwen"})
    void duplicateIds_areRejected(String provider) {
        ObjectNode message = mapper.createObjectNode();
        message.putArray("tool_calls").add(call("call-1", "search_materials", "{}"))
                .add(call("call-1", "read_history", "{}"));
        assertThatThrownBy(() -> response(provider, root(message)))
                .isInstanceOf(LlmException.class).hasMessageContaining("id 重复");
    }

    @ParameterizedTest
    @ValueSource(strings = {"deepseek", "qwen"})
    void requestBody_keepsAssistantToolCallAndToolResultMessages(String provider) {
        List<Map<String, Object>> messages = List.of(
                Map.of("role", "assistant", "content", "", "reasoning_content", "需核实资料",
                        "tool_calls", List.of(Map.of("id", "call-1", "type", "function",
                                "function", Map.of("name", "read_history", "arguments", "{}")))),
                Map.of("role", "tool", "tool_call_id", "call-1", "content", "已考察索引"));
        ChatRequest request = new ChatRequest("test", messages, true, false,
                List.of(Map.of("type", "function")), 4096);
        Map<String, Object> body = "deepseek".equals(provider)
                ? new DeepSeekChatProvider(new OkHttpClient(), mapper, new LlmProperties()).buildRequestBody(request)
                : new QwenChatProvider(new OkHttpClient(), mapper, new DashscopeProperties()).buildRequestBody(request);
        assertThat(body.get("messages")).isEqualTo(messages);
        assertThat(body.get("tools")).isEqualTo(request.tools());
    }
}
