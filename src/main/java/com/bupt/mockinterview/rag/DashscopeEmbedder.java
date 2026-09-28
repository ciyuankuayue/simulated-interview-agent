package com.bupt.mockinterview.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 通义千问 embedding（dashscope compatible-mode/v1 的 OpenAI 兼容 /embeddings 接口）。
 * 需 {@code DASHSCOPE_API_KEY}；沙盒无限流、未 set 密钥时不可用，离线逻辑用 {@link FakeEmbedder} 验。
 *
 * ⚠️ 响应解析按 OpenAI 兼容格式 {@code data[0].embedding} 写；真实联调若字段不符（如原生 output.embeddings），
 *    以实际返回 JSON 为准微调。模型串 {@code qwen3.7-text-embedding-flash}（application.yml 配置）。
 */
public class DashscopeEmbedder implements Embedder {

    private static final MediaType JSON = MediaType.get("application/json");
    private static final int DIM = 1024; // 通义 text-embedding 默认 1024 维；以真实返回为准

    private final OkHttpClient client;
    private final String baseUrl;
    private final Supplier<String> apiKeySupplier;
    private final String model;
    private final ObjectMapper mapper = new ObjectMapper();

    /** 固定 key 构造（测试 / 离线联调用）。 */
    public DashscopeEmbedder(OkHttpClient client, String baseUrl, String apiKey, String model) {
        this(client, baseUrl, () -> apiKey, model);
    }

    /**
     * 动态 key 构造（生产路径）：每次 embed 现取，页面保存/解锁密钥后无需重启即热生效。
     */
    public DashscopeEmbedder(OkHttpClient client, String baseUrl, Supplier<String> apiKeySupplier, String model) {
        this.client = client;
        this.baseUrl = baseUrl;
        this.apiKeySupplier = apiKeySupplier;
        this.model = model;
    }

    @Override
    public String name() {
        return "dashscope:" + model;
    }

    @Override
    public float[] embed(String text) throws Exception {
        String apiKey = apiKeySupplier.get();
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("DashScope 密钥未配置：请在页面「模型密钥」中填写，或设置 DASHSCOPE_API_KEY");
        }
        // OpenAI 兼容接口：input 直接是字符串（或字符串数组），不是原生 {"texts":[...]}
        String body = mapper.writeValueAsString(Map.of(
                "model", model,
                "input", text,
                "encoding_format", "float"
        ));
        Request req = new Request.Builder()
                .url(baseUrl + "/embeddings")
                .addHeader("Authorization", "Bearer " + apiKey)
                .post(RequestBody.create(body, JSON))
                .build();
        try (Response resp = client.newCall(req).execute()) {
            if (!resp.isSuccessful() || resp.body() == null) {
                String err = resp.body() == null ? "<null body>" : resp.body().string();
                throw new RuntimeException("通义 embedding 调用失败: HTTP " + resp.code() + " | " + err);
            }
            JsonNode root = mapper.readTree(resp.body().string());
            JsonNode data = root.path("data");
            if (data.isArray() && data.size() > 0) {
                JsonNode emb = data.get(0).path("embedding");
                float[] v = new float[emb.size()];
                for (int i = 0; i < emb.size(); i++) {
                    v[i] = (float) emb.get(i).asDouble();
                }
                return v;
            }
            throw new RuntimeException("通义 embedding 返回格式异常: " + root);
        }
    }
}
