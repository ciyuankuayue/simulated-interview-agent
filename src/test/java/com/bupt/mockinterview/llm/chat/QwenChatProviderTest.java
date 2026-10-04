package com.bupt.mockinterview.llm.chat;

import com.bupt.mockinterview.llm.LlmException;
import com.bupt.mockinterview.rag.DashscopeProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * R3-2 QwenChatProvider 纯单测（不打真实网络）：
 * 请求体形状（enable_thinking 顶层布尔、无 thinking 对象、json_mode/tools/max_tokens）、
 * 响应映射、空 key 人话报错、4xx 不重试（用 JDK 内置 HttpServer 计数）。
 */
class QwenChatProviderTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private QwenChatProvider provider(DashscopeProperties props) {
        return new QwenChatProvider(new OkHttpClient(), mapper, props);
    }

    private ChatRequest req(boolean thinking, boolean jsonMode, List<Map<String, Object>> tools) {
        return new ChatRequest("qwen-test",
                List.of(Map.of("role", "user", "content", "你好")),
                thinking, jsonMode, tools, 2048);
    }

    @Test
    void buildRequestBody_thinkingJson_usesEnableThinkingFlag_andNoDeepSeekThinkingObject() {
        Map<String, Object> body = provider(new DashscopeProperties())
                .buildRequestBody(req(true, true, null));

        assertThat(body.get("model")).isEqualTo("qwen-test");
        assertThat(body.get("max_tokens")).isEqualTo(2048);
        assertThat(body.get("enable_thinking")).isEqualTo(true);
        assertThat(body).doesNotContainKey("thinking");
        assertThat(((Map<?, ?>) body.get("response_format")).get("type")).isEqualTo("json_object");
    }

    @Test
    void buildRequestBody_nonThinkingNoJson_andTools() {
        List<Map<String, Object>> tools = List.of(Map.of("type", "function"));
        Map<String, Object> body = provider(new DashscopeProperties())
                .buildRequestBody(req(false, false, tools));

        assertThat(body.get("enable_thinking")).isEqualTo(false);
        assertThat(body).doesNotContainKey("response_format");
        assertThat(body.get("tools")).isEqualTo(tools);
    }

    @Test
    void toResponse_mapsContentReasoningAndUsage() throws Exception {
        JsonNode root = mapper.readTree("""
                {"choices":[{"message":{"content":"你好","reasoning_content":"先想想"}}],
                 "usage":{"prompt_tokens":5,"completion_tokens":3,"total_tokens":8}}
                """);
        ChatResponse resp = provider(new DashscopeProperties()).toResponse(root);

        assertThat(resp.content()).isEqualTo("你好");
        assertThat(resp.reasoning()).isEqualTo("先想想");
        assertThat(resp.usage().path("total_tokens").asLong()).isEqualTo(8L);
    }

    @Test
    void toResponse_missingMessage_throws() throws Exception {
        JsonNode root = mapper.readTree("{\"choices\":[]}");
        assertThatThrownBy(() -> provider(new DashscopeProperties()).toResponse(root))
                .isInstanceOf(LlmException.class);
    }

    @Test
    void chat_blankKey_throwsHumanHint_beforeHttpRequest() {
        assertThatThrownBy(() -> provider(new DashscopeProperties()).chat(req(false, false, null), "  "))
                .isInstanceOf(LlmException.class)
                .hasMessageContaining("DASHSCOPE_API_KEY");
    }

    @Test
    void http4xx_isNotRetried() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            hits.incrementAndGet();
            byte[] b = "{\"error\":\"bad request\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(400, b.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(b);
            }
        });
        server.start();
        try {
            DashscopeProperties props = new DashscopeProperties();
            props.setBaseUrl("http://localhost:" + server.getAddress().getPort() + "/v1");
            assertThatThrownBy(() -> provider(props).chat(req(false, false, null), "sk-x"))
                    .isInstanceOf(LlmException.class)
                    .hasMessageContaining("不可重试");
            assertThat(hits.get()).as("4xx 不应重试").isEqualTo(1);
        } finally {
            server.stop(0);
        }
    }
}
