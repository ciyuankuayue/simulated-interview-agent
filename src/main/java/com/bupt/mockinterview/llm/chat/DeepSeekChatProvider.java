package com.bupt.mockinterview.llm.chat;

import com.bupt.mockinterview.llm.LlmException;
import com.bupt.mockinterview.llm.LlmProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * DeepSeek 对话通道（R3-1 从 LlmService 原样迁入，行为零变化）：
 * 单例 OkHttp 派生超时 client + 4xx 不重试 / 5xx、网络异常重试 3 次。
 *
 * <p>DeepSeek 私有协议点：思考开关是对象 {@code thinking:{type:enabled/disabled}}，
 * 官方默认开启思考，故非思考必须显式 disabled；思考模式下不支持 temperature 等参数，一律不传。
 * 思维链回 {@code choices[0].message.reasoning_content}。
 */
@Component
public class DeepSeekChatProvider implements ChatProvider {

    public static final String ID = "deepseek";

    private static final MediaType JSON = MediaType.get("application/json");
    private static final int MAX_RETRY = 3;

    private final OkHttpClient client;
    private final ObjectMapper mapper;
    private final LlmProperties props;

    public DeepSeekChatProvider(OkHttpClient okHttpClient, ObjectMapper mapper, LlmProperties props) {
        this.mapper = mapper;
        this.props = props;
        // 由全局单例派生：共享连接池与线程池，仅覆盖超时
        this.client = okHttpClient.newBuilder()
                .callTimeout(props.getTimeoutMs(), TimeUnit.MILLISECONDS)
                .build();
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public ChatResponse chat(ChatRequest req, String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new LlmException("DeepSeek 密钥未配置：平台模式请让运维设置 DEEPSEEK_API_KEY，或在「模型调用方式」中填写你自己的 API Key");
        }
        Map<String, Object> body = buildRequestBody(req);
        JsonNode root = callWithRetry(body, apiKey);
        return toResponse(root);
    }

    @Override
    public ChatResponse stream(ChatRequest req, String apiKey, Consumer<String> onDelta) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new LlmException("DeepSeek 密钥未配置：平台模式请让运维设置 DEEPSEEK_API_KEY，或在「模型调用方式」中填写你自己的 API Key");
        }
        return OpenAiSseStream.call(client, mapper, props.getBaseUrl() + "/chat/completions",
                buildRequestBody(req), apiKey, "DeepSeek", onDelta);
    }

    /** 响应映射：完整保留工具请求，供上层有限 ReAct 循环使用。 */
    ChatResponse toResponse(JsonNode root) {
        if (root == null) {
            throw new LlmException("DeepSeek 响应为空");
        }
        JsonNode msg = root.path("choices").path(0).path("message");
        if (!msg.isObject()) {
            throw new LlmException("DeepSeek 响应缺少 choices[0].message: " + root);
        }
        String content = msg.path("content").asText("");
        String reasoning = msg.path("reasoning_content").asText(null);
        return new ChatResponse(content, reasoning, root.path("usage"), ToolCallParser.parse(msg, mapper));
    }

    /** 组装 DeepSeek 请求体（包可见便于单测锁定形状）。 */
    Map<String, Object> buildRequestBody(ChatRequest req) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", req.model());
        body.put("messages", req.messages());
        body.put("max_tokens", req.maxTokens());
        body.put("thinking", Map.of("type", req.thinking() ? "enabled" : "disabled"));
        if (req.jsonMode()) {
            body.put("response_format", Map.of("type", "json_object"));
        }
        if (req.tools() != null && !req.tools().isEmpty()) {
            body.put("tools", req.tools());
        }
        // 思考模式不支持 temperature / presence_penalty / frequency_penalty，一律不传
        return body;
    }

    // ---------- HTTP + 重试（从 LlmService 原样迁入） ----------

    private JsonNode callWithRetry(Map<String, Object> body, String apiKey) {
        LlmException last = null;
        for (int attempt = 1; attempt <= MAX_RETRY; attempt++) {
            try {
                return callOnce(body, apiKey);
            } catch (HttpStatusException e) {
                // 4xx 属请求本身有问题，重试无意义
                if (e.code < 500) {
                    throw new LlmException("DeepSeek 调用失败（不可重试）: HTTP " + e.code + " | " + e.body, e);
                }
                last = new LlmException("DeepSeek 服务端错误（第 " + attempt + " 次）: HTTP " + e.code + " | " + e.body, e);
            } catch (IOException e) {
                last = new LlmException("DeepSeek 调用网络异常（第 " + attempt + " 次）: " + e.getMessage(), e);
            }
            if (attempt < MAX_RETRY) {
                sleepQuietly(500L * attempt);
            }
        }
        throw last;
    }

    private JsonNode callOnce(Map<String, Object> body, String apiKey) throws IOException {
        String json = mapper.writeValueAsString(body);
        Request req = new Request.Builder()
                .url(props.getBaseUrl() + "/chat/completions")
                .addHeader("Authorization", "Bearer " + apiKey)
                .post(RequestBody.create(json, JSON))
                .build();
        try (Response resp = client.newCall(req).execute()) {
            String respBody = resp.body() == null ? "" : resp.body().string();
            if (!resp.isSuccessful()) {
                throw new HttpStatusException(resp.code(), respBody);
            }
            return mapper.readTree(respBody);
        }
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 仅用于在重试逻辑里区分「可重试的 5xx」与「不可重试的 4xx」。 */
    private static class HttpStatusException extends RuntimeException {
        private final int code;
        private final String body;

        HttpStatusException(int code, String body) {
            super("HTTP " + code);
            this.code = code;
            this.body = body;
        }
    }
}
