package com.bupt.mockinterview.llm.chat;

import com.bupt.mockinterview.llm.LlmException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okio.BufferedSource;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/** 两家 OpenAI 兼容通道共享 SSE 解码；网络分片、UTF-8 和 CRLF 由 Okio 处理。 */
final class OpenAiSseStream {
    private static final MediaType JSON = MediaType.get("application/json");
    private static final int MAX_ATTEMPTS = 3;
    private static final long MAX_EVENT_BYTES = 1_048_576;

    private OpenAiSseStream() {}

    static ChatResponse call(OkHttpClient client, ObjectMapper mapper, String url,
                             Map<String, Object> requestBody, String apiKey, String provider,
                             Consumer<String> onDelta) {
        Objects.requireNonNull(onDelta, "onDelta");
        Map<String, Object> body = new LinkedHashMap<>(requestBody);
        body.put("stream", true);
        body.put("stream_options", Map.of("include_usage", true));
        // 当前入口只流式展示普通文本，工具参数不能误当成用户回答。
        if (body.containsKey("tools")) {
            throw new LlmException("流式正文调用不支持工具请求");
        }
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            State state = new State();
            boolean retryable = false;
            try {
                Request request = new Request.Builder().url(url)
                        .header("Authorization", "Bearer " + apiKey)
                        .header("Accept", "text/event-stream")
                        .post(RequestBody.create(mapper.writeValueAsString(body), JSON)).build();
                try (Response response = client.newCall(request).execute()) {
                    if (!response.isSuccessful()) {
                        retryable = response.code() >= 500;
                        throw new LlmException(provider + " 流式调用失败：HTTP " + response.code());
                    }
                    if (response.body() == null) {
                        throw new IOException("响应流为空");
                    }
                    read(response.body().source(), mapper, state, onDelta);
                    if (!state.done && !state.finished) {
                        throw new IOException("模型响应流提前结束");
                    }
                    if (state.content.toString().isBlank()) {
                        throw new LlmException("模型响应正文为空");
                    }
                    return state.response();
                }
            } catch (IOException e) {
                retryable = true;
                if (state.hasModelData() || attempt == MAX_ATTEMPTS) {
                    throw failure(provider, e, state);
                }
            } catch (RuntimeException e) {
                // 包括前端断开时 callback 抛出的异常。收到过内容就绝不能重试/重放。
                if (state.hasModelData() || !retryable || attempt == MAX_ATTEMPTS) {
                    throw failure(provider, e, state);
                }
            }
            if (retryable) {
                try {
                    Thread.sleep(250L * attempt);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw failure(provider, e, state);
                }
            }
        }
        throw new LlmException(provider + " 流式调用失败");
    }

    private static ChatStreamException failure(String provider, Exception cause, State state) {
        return new ChatStreamException(provider + " 流式回复未完成：" + cause.getMessage(), cause,
                state.hasModelData() ? state.response() : null);
    }

    private static void read(BufferedSource source, ObjectMapper mapper, State state,
                             Consumer<String> onDelta) throws IOException {
        StringBuilder data = new StringBuilder();
        while (!state.done) {
            if (source.exhausted()) {
                if (!data.isEmpty()) {
                    event(data.toString(), mapper, state, onDelta);
                }
                return;
            }
            String line = source.readUtf8LineStrict(MAX_EVENT_BYTES);
            if (line.isEmpty()) {
                if (!data.isEmpty()) {
                    event(data.toString(), mapper, state, onDelta);
                    data.setLength(0);
                }
            } else if (line.startsWith("data:")) {
                String value = line.substring(5);
                if (value.startsWith(" ")) value = value.substring(1);
                if (!data.isEmpty()) data.append('\n');
                data.append(value);
                if (data.length() > MAX_EVENT_BYTES) {
                    throw new LlmException("模型响应事件过大");
                }
            }
        }
    }

    private static void event(String data, ObjectMapper mapper, State state,
                              Consumer<String> onDelta) throws IOException {
        if ("[DONE]".equals(data.trim())) {
            state.done = true;
            return;
        }
        JsonNode root;
        try {
            root = mapper.readTree(data);
        } catch (IOException e) {
            // 协议错误不会因为重试而恢复，也不能重复已呈现的部分。
            throw new LlmException("模型响应流包含无效 JSON", e);
        }
        if (root == null || !root.isObject()) {
            throw new LlmException("模型响应流事件格式错误");
        }
        JsonNode usage = root.path("usage");
        if (usage.isObject()) state.usage = usage;
        if (root.hasNonNull("error")) {
            throw new LlmException("模型返回流式错误");
        }
        JsonNode choice = root.path("choices").path(0);
        JsonNode delta = choice.path("delta");
        if (delta.path("tool_calls").isArray() && !delta.path("tool_calls").isEmpty()) {
            throw new LlmException("流式正文调用收到意外工具请求");
        }
        if (delta.path("reasoning_content").isTextual()) {
            state.reasoning.append(delta.path("reasoning_content").textValue());
        }
        if (delta.path("content").isTextual()) {
            String text = delta.path("content").textValue();
            if (!text.isEmpty()) {
                state.content.append(text);
                onDelta.accept(text);
            }
        }
        if (choice.hasNonNull("finish_reason")) {
            String reason = choice.path("finish_reason").asText();
            if (!"stop".equals(reason)) {
                throw new LlmException("模型回复未正常结束：" + reason);
            }
            state.finished = true;
        }
    }

    private static final class State {
        private final StringBuilder content = new StringBuilder();
        private final StringBuilder reasoning = new StringBuilder();
        private JsonNode usage;
        private boolean done;
        private boolean finished;

        boolean hasModelData() {
            return !content.isEmpty() || !reasoning.isEmpty() || usage != null;
        }

        ChatResponse response() {
            return new ChatResponse(content.toString(), reasoning.isEmpty() ? null : reasoning.toString(), usage);
        }
    }
}
