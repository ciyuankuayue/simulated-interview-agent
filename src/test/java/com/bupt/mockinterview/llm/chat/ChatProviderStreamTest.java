package com.bupt.mockinterview.llm.chat;

import com.bupt.mockinterview.llm.LlmProperties;
import com.bupt.mockinterview.rag.DashscopeProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import okhttp3.OkHttpClient;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChatProviderStreamTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @ParameterizedTest
    @ValueSource(strings = {"deepseek", "qwen"})
    void streamsBeforeCompletionAndDecodesSplitUtf8WithUsageTail(String providerId) throws Exception {
        AtomicReference<JsonNode> request = new AtomicReference<>();
        CountDownLatch firstDelta = new CountDownLatch(1);
        AtomicReference<Throwable> serverFailure = new AtomicReference<>();
        HttpServer server = server();
        server.createContext("/v1/chat/completions", exchange -> {
            try {
                request.set(mapper.readTree(exchange.getRequestBody()));
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, 0);
                try (OutputStream out = exchange.getResponseBody()) {
                    writeSplit(out, ": heartbeat\r\n\r\n"
                            + event("{\"choices\":[{\"delta\":{\"reasoning_content\":\"内部思考\"}}]}")
                            + event("{\"choices\":[{\"delta\":{\"content\":\"你好\"}}]}"));
                    // 没有收到 delta 就不发送结尾：完整缓冲实现会在这里失败。
                    if (!firstDelta.await(3, TimeUnit.SECONDS)) {
                        serverFailure.set(new AssertionError("完成前没有收到正文 delta"));
                    }
                    writeSplit(out, event("{\"choices\":[{\"delta\":{\"content\":\"呀\"},\"finish_reason\":\"stop\"}]}")
                            + event("{\"choices\":[],\"usage\":{\"prompt_tokens\":11,\"completion_tokens\":7,\"total_tokens\":18}}")
                            + "data: [DONE]\r\n\r\n");
                }
            } catch (Throwable e) {
                serverFailure.set(e);
                exchange.close();
            }
        });
        server.start();
        try {
            List<String> deltas = new ArrayList<>();
            ChatResponse response = provider(providerId, server).stream(req(), "offline", text -> {
                deltas.add(text);
                firstDelta.countDown();
            });
            assertThat(serverFailure.get()).isNull();
            assertThat(deltas).containsExactly("你好", "呀");
            assertThat(response.content()).isEqualTo("你好呀");
            assertThat(response.reasoning()).isEqualTo("内部思考");
            assertThat(response.usage().path("total_tokens").asLong()).isEqualTo(18);
            assertThat(request.get().path("stream").asBoolean()).isTrue();
            assertThat(request.get().path("stream_options").path("include_usage").asBoolean()).isTrue();
            if (providerId.equals("qwen")) {
                assertThat(request.get().path("enable_thinking").asBoolean()).isFalse();
                assertThat(request.get().has("thinking")).isFalse();
            } else {
                assertThat(request.get().path("thinking").path("type").asText()).isEqualTo("disabled");
            }
        } finally {
            server.stop(0);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"deepseek", "qwen"})
    void interruptedStreamNeverReplaysAndKeepsPartialForBilling(String providerId) throws Exception {
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = server();
        server.createContext("/v1/chat/completions", exchange -> {
            calls.incrementAndGet();
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream out = exchange.getResponseBody()) {
                writeSplit(out, event("{\"choices\":[{\"delta\":{\"content\":\"已收到\"}}]}"));
            }
        });
        server.start();
        try {
            List<String> deltas = new ArrayList<>();
            assertThatThrownBy(() -> provider(providerId, server).stream(req(), "offline", deltas::add))
                    .isInstanceOfSatisfying(ChatStreamException.class, e -> {
                        assertThat(e.partialResponse().content()).isEqualTo("已收到");
                        assertThat(e.partialResponse().usage()).isNull();
                    });
            assertThat(deltas).containsExactly("已收到");
            assertThat(calls.get()).isEqualTo(1);
        } finally {
            server.stop(0);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"deepseek", "qwen"})
    void consumerDisconnectIsWrappedWithPartialAndNeverRetried(String providerId) throws Exception {
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = fixture(calls, 200, event("{\"choices\":[{\"delta\":{\"content\":\"第一段\"}}]}")
                + event("{\"choices\":[{\"delta\":{\"content\":\"第二段\"},\"finish_reason\":\"stop\"}]}")
                + "data: [DONE]\n\n");
        try {
            assertThatThrownBy(() -> provider(providerId, server).stream(req(), "offline", text -> {
                throw new UncheckedIOException(new IOException("client disconnected"));
            })).isInstanceOfSatisfying(ChatStreamException.class, e -> {
                assertThat(e.partialResponse().content()).isEqualTo("第一段");
                assertThat(e.getCause()).isInstanceOf(UncheckedIOException.class);
            });
            assertThat(calls.get()).isEqualTo(1);
        } finally {
            server.stop(0);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"deepseek", "qwen"})
    void streamErrorsAndTokenLimitNeverReturnTruncatedSuccess(String providerId) throws Exception {
        for (String ending : List.of(
                event("{\"error\":{\"message\":\"upstream error\"}}"),
                event("{\"choices\":[{\"delta\":{},\"finish_reason\":\"length\"}]}"))) {
            AtomicInteger calls = new AtomicInteger();
            HttpServer server = fixture(calls, 200,
                    event("{\"choices\":[{\"delta\":{\"content\":\"半句\"}}]}") + ending + "data: [DONE]\n\n");
            try {
                assertThatThrownBy(() -> provider(providerId, server).stream(req(), "offline", text -> {}))
                        .isInstanceOfSatisfying(ChatStreamException.class,
                                e -> assertThat(e.partialResponse().content()).isEqualTo("半句"));
                assertThat(calls.get()).isEqualTo(1);
            } finally {
                server.stop(0);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"deepseek", "qwen"})
    void retriesServerErrorsBeforeOutputButNeverClientErrors(String providerId) throws Exception {
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = server();
        server.createContext("/v1/chat/completions", exchange -> {
            int attempt = calls.incrementAndGet();
            byte[] bytes = (attempt == 1 ? "unavailable" : event("{\"choices\":[{\"delta\":{\"content\":\"成功\"},\"finish_reason\":\"stop\"}]}")
                    + "data: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(attempt == 1 ? 503 : 200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) { out.write(bytes); }
        });
        server.start();
        try {
            assertThat(provider(providerId, server).stream(req(), "offline", text -> {}).content()).isEqualTo("成功");
            assertThat(calls.get()).isEqualTo(2);
        } finally {
            server.stop(0);
        }
        calls.set(0);
        HttpServer bad = fixture(calls, 401, "unauthorized");
        try {
            assertThatThrownBy(() -> provider(providerId, bad).stream(req(), "offline", text -> {}))
                    .isInstanceOfSatisfying(ChatStreamException.class,
                            e -> assertThat(e.partialResponse()).isNull());
            assertThat(calls.get()).isEqualTo(1);
        } finally {
            bad.stop(0);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"deepseek", "qwen"})
    void emptyAnswerIsRejectedWithUsagePreserved(String providerId) throws Exception {
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = fixture(calls, 200,
                event("{\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}")
                        + event("{\"choices\":[],\"usage\":{\"total_tokens\":6}}") + "data: [DONE]\n\n");
        try {
            assertThatThrownBy(() -> provider(providerId, server).stream(req(), "offline", text -> {}))
                    .isInstanceOfSatisfying(ChatStreamException.class,
                            e -> assertThat(e.partialResponse().usage().path("total_tokens").asLong()).isEqualTo(6));
            assertThat(calls.get()).isEqualTo(1);
        } finally {
            server.stop(0);
        }
    }

    private HttpServer fixture(AtomicInteger calls, int status, String body) throws IOException {
        HttpServer server = server();
        server.createContext("/v1/chat/completions", exchange -> {
            calls.incrementAndGet();
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) { out.write(bytes); }
        });
        server.start();
        return server;
    }

    private HttpServer server() throws IOException {
        return HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    }

    private ChatProvider provider(String id, HttpServer server) {
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
        if (id.equals("qwen")) {
            DashscopeProperties props = new DashscopeProperties();
            props.setBaseUrl(baseUrl);
            return new QwenChatProvider(new OkHttpClient(), mapper, props);
        }
        LlmProperties props = new LlmProperties();
        props.setBaseUrl(baseUrl);
        return new DeepSeekChatProvider(new OkHttpClient(), mapper, props);
    }

    private ChatRequest req() {
        return new ChatRequest("fixture", List.of(Map.of("role", "user", "content", "你好")), false, false, null, 4096);
    }

    private String event(String json) { return "data: " + json + "\r\n\r\n"; }

    private void writeSplit(OutputStream out, String text) throws IOException {
        for (byte b : text.getBytes(StandardCharsets.UTF_8)) {
            out.write(b & 0xff);
            out.flush();
        }
    }
}
