package com.bupt.mockinterview.llm.chat;

import com.bupt.mockinterview.llm.LlmException;
import com.bupt.mockinterview.rag.DashscopeProperties;
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
 * 通义千问对话通道（R3-2）：DashScope OpenAI 兼容模式 {@code /chat/completions}，
 * 与 {@link com.bupt.mockinterview.rag.DashscopeEmbedder} 共用 base-url 与平台 key
 * （{@code DASHSCOPE_API_KEY}；BYOK 用户选 Qwen 也走平台 key）。
 *
 * <p>与 DeepSeek 的协议差异（本类内部消化，不泄漏到上层）：
 * <ul>
 *   <li>思考开关是**顶层布尔** {@code enable_thinking}，不是 {@code thinking:{type:…}} 对象；</li>
 *   <li>思维链仍回 {@code choices[0].message.reasoning_content}；</li>
 *   <li>{@code response_format:json_object}、{@code messages}、{@code max_tokens}、usage 与 OpenAI 同名。</li>
 * </ul>
 *
 * ⚠️ 判分/Critic 用的「enable_thinking=true + json_object」是最易翻车组合（部分模型互斥或挤占正文），
 * reasoner 模型必须经真机验证（见 R3 计划批 3 第 0 步）。
 * 重试语义与 DeepSeek 对齐：4xx 不重试，5xx/IO 重试 3 次。
 */
@Component
public class QwenChatProvider implements ChatProvider {

    public static final String ID = "qwen";

    private static final MediaType JSON = MediaType.get("application/json");
    private static final int MAX_RETRY = 3;

    private final OkHttpClient client;
    private final ObjectMapper mapper;
    private final DashscopeProperties props;

    public QwenChatProvider(OkHttpClient okHttpClient, ObjectMapper mapper, DashscopeProperties props) {
        this.mapper = mapper;
        this.props = props;
        this.client = okHttpClient.newBuilder()
                // 显式三档超时：思考模型首 token 前可能静默 10s+（实测 reasoner 约 13s），
                // readTimeout 必须 > 该静默窗口；不依赖注入客户端的默认值（裸 new OkHttpClient() 仅 10s）。
                .connectTimeout(10_000, TimeUnit.MILLISECONDS)
                .readTimeout(60_000, TimeUnit.MILLISECONDS)
                .callTimeout(90_000, TimeUnit.MILLISECONDS)
                .build();
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public ChatResponse chat(ChatRequest req, String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new LlmException("通义千问密钥未配置：请让运维设置环境变量 DASHSCOPE_API_KEY（对话与向量化共用同一个平台 key）");
        }
        Map<String, Object> body = buildRequestBody(req);
        JsonNode root = callWithRetry(body, apiKey);
        return toResponse(root);
    }

    @Override
    public ChatResponse stream(ChatRequest req, String apiKey, Consumer<String> onDelta) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new LlmException("通义千问密钥未配置：请让运维设置环境变量 DASHSCOPE_API_KEY（对话与向量化共用同一个平台 key）");
        }
        return OpenAiSseStream.call(client, mapper, props.getBaseUrl() + "/chat/completions",
                buildRequestBody(req), apiKey, "通义千问", onDelta);
    }

    /** 组装 Qwen 兼容模式请求体（包可见便于单测锁定形状）。 */
    Map<String, Object> buildRequestBody(ChatRequest req) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", req.model());
        body.put("messages", req.messages());
        body.put("max_tokens", req.maxTokens());
        // 通义：顶层布尔开关（与 DeepSeek 的 thinking 对象不同）
        body.put("enable_thinking", req.thinking());
        if (req.jsonMode()) {
            body.put("response_format", Map.of("type", "json_object"));
        }
        if (req.tools() != null && !req.tools().isEmpty()) {
            body.put("tools", req.tools());
        }
        // 思考模式下不传 temperature 等采样参数（与 DeepSeek 同样约束）
        return body;
    }

    /** 响应映射（包可见便于单测）：OpenAI 兼容形状，思维链取 reasoning_content。 */
    ChatResponse toResponse(JsonNode root) {
        if (root == null) {
            throw new LlmException("通义千问响应为空");
        }
        JsonNode msg = root.path("choices").path(0).path("message");
        if (!msg.isObject()) {
            throw new LlmException("通义千问响应缺少 choices[0].message: " + root);
        }
        String content = msg.path("content").asText("");
        String reasoning = msg.path("reasoning_content").asText(null);
        return new ChatResponse(content, reasoning, root.path("usage"), ToolCallParser.parse(msg, mapper));
    }

    // ---------- HTTP + 重试（与 DeepSeekChatProvider 同骨架） ----------

    private JsonNode callWithRetry(Map<String, Object> body, String apiKey) {
        LlmException last = null;
        for (int attempt = 1; attempt <= MAX_RETRY; attempt++) {
            com.bupt.mockinterview.billing.UsageContext.noteAttempt();
            try {
                return callOnce(body, apiKey);
            } catch (HttpStatusException e) {
                if (e.code < 500) {
                    throw new LlmException("通义千问调用失败（不可重试）: HTTP " + e.code + " | " + e.body, e);
                }
                last = new LlmException("通义千问服务端错误（第 " + attempt + " 次）: HTTP " + e.code + " | " + e.body, e);
            } catch (IOException e) {
                last = new LlmException("通义千问调用网络异常（第 " + attempt + " 次）: " + e.getMessage(), e);
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
