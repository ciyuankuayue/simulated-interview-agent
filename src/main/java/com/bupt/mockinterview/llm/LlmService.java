package com.bupt.mockinterview.llm;

import com.bupt.mockinterview.billing.QuotaExhaustedException;
import com.bupt.mockinterview.billing.QuotaProperties;
import com.bupt.mockinterview.billing.QuotaService;
import com.bupt.mockinterview.billing.UsageEstimates;
import com.bupt.mockinterview.billing.UsageScene;
import com.bupt.mockinterview.domain.User;
import com.bupt.mockinterview.security.CurrentUserService;
import com.bupt.mockinterview.security.KeyResolver;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * DeepSeek 调用封装（T5）：单例 OkHttp + 超时重试 + 两个入口。
 *
 * 两个入口对应 domain.md §4.1 的分工：
 * - {@link #followUp}：追问 —— Flash **非思考模式**（快，实时交互），只产出一句追问/纠正。
 * - {@link #score}：判分 —— Flash **思考模式**（默认即开，故显式传 enabled）+ JSON 输出，
 *   产出 {@code {score, breakdown, missed_points, followup}} 并校验非空/越界。
 *
 * 失败语义：重试耗尽后抛 {@link LlmException}，**降级策略交给 T6 agent 层**
 * （如追问失败就跳过追问直接下一题，不让整场面试中断）。
 */
@Service
public class LlmService implements LlmPort {

    private static final MediaType JSON = MediaType.get("application/json");
    private static final int MAX_RETRY = 3;
    private static final Logger log = LoggerFactory.getLogger(LlmService.class);

    /** 判分 system prompt：4 维 × 0-2 = 0-8（domain.md §4），强制纯 JSON 输出。 */
    private static final String SCORE_SYSTEM = """
            你是资深 Java 后端面试官，负责为候选人对一道面试题的回答打分。
            评分共 4 个维度，每维 0-2 分，总分 0-8：正确性、完整性、深度、实证。
            只输出 JSON，不要任何多余文字，不要 markdown 代码块。格式：
            {"score": 0到8的整数,
             "breakdown": {"正确性":0到2,"完整性":0到2,"深度":0到2,"实证":0到2},
             "missed_points": ["回答漏掉的关键点"],
             "knowledge_point": "本题考察的核心知识点，10-20 字短标签，措辞要稳定可复用（如「MySQL 索引失效场景」「synchronized 锁升级」）",
             "followup": "下一句追问（一句话）；若 score>=8 则填空字符串"}
            """;

    private final OkHttpClient client;
    private final ObjectMapper mapper;
    private final LlmProperties props;
    private final KeyResolver keyResolver;
    private final CurrentUserService currentUser;
    private final QuotaService quotaService;
    private final QuotaProperties quotaProps;

    public LlmService(OkHttpClient okHttpClient, ObjectMapper mapper, LlmProperties props,
                      KeyResolver keyResolver, CurrentUserService currentUser,
                      QuotaService quotaService, QuotaProperties quotaProps) {
        this.mapper = mapper;
        this.props = props;
        this.keyResolver = keyResolver;
        this.currentUser = currentUser;
        this.quotaService = quotaService;
        this.quotaProps = quotaProps;
        // 由全局单例派生：共享连接池与线程池，仅覆盖超时
        this.client = okHttpClient.newBuilder()
                .callTimeout(props.getTimeoutMs(), TimeUnit.MILLISECONDS)
                .build();
    }

    // ---------- 两个入口 ----------

    /** 追问/实时交互：非思考模式，返回一句追问或纠正。 */
    public LlmReply followUp(String system, String user) {
        return chat(props.getModelFlash(), false, List.of(sys(system), userMsg(user)),
                null, false, props.getMaxTokens(), UsageScene.FOLLOWUP);
    }

    /** 判分：思考模式 + JSON 输出，解析并校验后返回结构化结果。 */
    public ScoreResult score(String question, String answer) {
        String user = "题目：\n" + question + "\n\n候选人的回答：\n" + answer;
        LlmReply reply = chat(props.getModelReasoner(), true, List.of(sys(SCORE_SYSTEM), userMsg(user)),
                null, true, props.getMaxTokens(), UsageScene.SCORE);
        return parseScore(reply.content());
    }

    // ---------- 通用 chat ----------

    public LlmReply chat(String model, boolean thinking, List<Map<String, Object>> messages,
                         List<Map<String, Object>> tools) {
        return chat(model, thinking, messages, tools, false, props.getMaxTokens(), UsageScene.CHAT);
    }

    public LlmReply chat(String model, boolean thinking, List<Map<String, Object>> messages,
                         List<Map<String, Object>> tools, boolean jsonMode) {
        return chat(model, thinking, messages, tools, jsonMode, props.getMaxTokens(), UsageScene.CHAT);
    }

    public LlmReply chat(String model, boolean thinking, List<Map<String, Object>> messages,
                         List<Map<String, Object>> tools, boolean jsonMode, int maxTokens) {
        return chat(model, thinking, messages, tools, jsonMode, maxTokens, UsageScene.CHAT);
    }

    /**
     * @param thinking 思考开关。官方默认开启，故两侧都显式传：非思考必须写 disabled。
     * @param jsonMode 是否强制 JSON 输出（判分用）。
     * @param tools    function calling 工具定义；非空时后续轮次须回传 reasoning_content，否则 400。
     * @param maxTokens 生成 token 上限（思考模式下限制 reasoning+content 总预算）。
     * @param scene    计费场景（R2 用量流水归类）。
     */
    public LlmReply chat(String model, boolean thinking, List<Map<String, Object>> messages,
                         List<Map<String, Object>> tools, boolean jsonMode, int maxTokens,
                         UsageScene scene) {
        // 统一在此解析 key 并计费：followUp/score/Critic/JD 解析等所有 DeepSeek 调用的唯一收口。
        User user = currentUser.currentUser();
        KeyResolver.ResolvedKey resolved = keyResolver.resolveForChat(user);
        boolean billable = user != null;
        if (billable) {
            if (resolved.byok()) {
                // BYOK 服务费在调用前扣（绝不超发）；余额不足抛 402，一次请求都不发出
                if (!quotaService.chargeForByokCall(user.getId())) {
                    throw QuotaExhaustedException.forMode(resolved.mode());
                }
            } else {
                // PLATFORM 预检：余额 0 直接 402，不烧请求
                quotaService.ensureAvailable(user.getId(), resolved.mode());
            }
        }
        requireApiKey(resolved.apiKey());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", messages);
        body.put("max_tokens", maxTokens);
        body.put("thinking", Map.of("type", thinking ? "enabled" : "disabled"));
        if (jsonMode) {
            body.put("response_format", Map.of("type", "json_object"));
        }
        if (tools != null && !tools.isEmpty()) {
            body.put("tools", tools);
        }
        // 思考模式不支持 temperature / presence_penalty / frequency_penalty，一律不传

        JsonNode root = callWithRetry(body, resolved.apiKey());
        JsonNode msg = root.path("choices").path(0).path("message");
        if (msg.isMissingNode()) {
            throw new LlmException("DeepSeek 响应缺少 choices[0].message: " + root);
        }
        String content = msg.path("content").asText("");
        String reasoning = msg.path("reasoning_content").asText(null);

        if (billable) {
            settleUsage(user.getId(), resolved.byok(), scene, model, messages, content, root.path("usage"));
        }
        return new LlmReply(content, reasoning, parseUsage(root.path("usage"), messages, content));
    }

    /** 用后计费：PLATFORM 按 usage 实扣；BYOK 只落流水（服务费已在调用前扣）。计费失败不吞掉用户已拿到的回复。 */
    private void settleUsage(Long userId, boolean byok, UsageScene scene, String model,
                             List<Map<String, Object>> messages, String content, JsonNode usageNode) {
        TokenUsage usage = parseUsage(usageNode, messages, content);
        if (usage == null) {
            // 厂商没回 usage 且关闭了估算兜底：无法准确计费，只告警不扣（宁可漏不可错停服务）
            log.warn("DeepSeek 响应缺 usage 且 app.quota.usage-estimate-enabled=false，本次未扣 token：scene={}", scene);
            return;
        }
        try {
            if (byok) {
                quotaService.recordByokChat(userId, scene, model,
                        usage.promptTokens(), usage.completionTokens(),
                        usage.totalTokens(), usage.estimated());
            } else {
                quotaService.settlePlatformCall(userId, scene, model,
                        usage.promptTokens(), usage.completionTokens(),
                        usage.totalTokens(), usage.estimated());
            }
        } catch (Exception e) {
            // token 已真实烧掉，回复也已生成：计费落库失败不能让用户看到 500（会诱发重复调用双倍烧钱）
            log.error("token 计费落库失败（请人工对账 llm_usage_log 缺口）：userId={} scene={} tokens={}",
                    userId, scene, usage.totalTokens(), e);
        }
    }

    /**
     * 解析 DeepSeek usage；缺失/异常时按字符估算兜底（受 app.quota.usage-estimate-enabled 控制）。
     * 返回 null = 无 usage 且不允许估算（调用方不扣费）。
     */
    private TokenUsage parseUsage(JsonNode usage, List<Map<String, Object>> messages, String content) {
        long prompt = usage.path("prompt_tokens").asLong(0L);
        long completion = usage.path("completion_tokens").asLong(0L);
        long total = usage.path("total_tokens").asLong(0L);
        if (total > 0L) {
            return new TokenUsage(prompt, completion, total, false);
        }
        if (!quotaProps.isUsageEstimateEnabled()) {
            return null;
        }
        // 字符估算：输入消息全文 + 输出内容，1 字符≈1 token（中文偏保守，防漏算）
        List<CharSequence> texts = new ArrayList<>();
        if (messages != null) {
            messages.forEach(m -> texts.add(String.valueOf(m.get("content"))));
        }
        texts.add(content);
        return new TokenUsage(0L, 0L, UsageEstimates.estimateChars(texts.toArray(new CharSequence[0])), true);
    }

    // ---------- HTTP + 重试 ----------

    /**
     * 校验一个「待保存」的 DeepSeek key 是否可用：GET {baseUrl}/models 最轻量
     * （不生成 token、不消耗对话额度），200=有效；401/403=key 无效；其余/网络异常=服务商不可达。
     * 注意：不走 {@link KeyResolver}、不扣 BYOK 点数——此刻用户可能还是 PLATFORM 模式。
     */
    @Override
    public ApiKeyStatus verifyApiKey(String candidateKey) {
        Request req = new Request.Builder()
                .url(props.getBaseUrl() + "/models")
                .addHeader("Authorization", "Bearer " + candidateKey)
                .get()
                .build();
        try (Response resp = client.newCall(req).execute()) {
            if (resp.isSuccessful()) {
                return ApiKeyStatus.VALID;
            }
            return (resp.code() == 401 || resp.code() == 403) ? ApiKeyStatus.INVALID : ApiKeyStatus.UNREACHABLE;
        } catch (IOException e) {
            return ApiKeyStatus.UNREACHABLE;
        }
    }

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

    // ---------- 判分解析 ----------

    private ScoreResult parseScore(String content) {
        String json = stripCodeFence(content);
        try {
            JsonNode root = mapper.readTree(json);
            int score = root.path("score").asInt(-1);
            if (score < 0 || score > 8) {
                throw new LlmException("判分 score 缺失或越界（应 0-8）: " + score + " | " + json);
            }
            Map<String, Integer> breakdown = new LinkedHashMap<>();
            JsonNode bd = root.path("breakdown");
            if (bd.isObject()) {
                bd.fields().forEachRemaining(e -> breakdown.put(e.getKey(), e.getValue().asInt(0)));
            }
            List<String> missed = new ArrayList<>();
            JsonNode mp = root.path("missed_points");
            if (mp.isArray()) {
                mp.forEach(n -> missed.add(n.asText()));
            }
            String followup = root.path("followup").asText("");
            String kp = root.path("knowledge_point").asText("");
            return new ScoreResult(score, breakdown, missed, followup, kp);
        } catch (LlmException e) {
            throw e;
        } catch (Exception e) {
            throw new LlmException("判分 JSON 解析失败: " + content, e);
        }
    }

    /** 模型偶尔会把 JSON 包在 ```json 代码块里，兜一下。 */
    private static String stripCodeFence(String s) {
        String t = s == null ? "" : s.trim();
        if (t.startsWith("```")) {
            int nl = t.indexOf('\n');
            int end = t.lastIndexOf("```");
            if (nl > 0 && end > nl) {
                t = t.substring(nl + 1, end).trim();
            }
        }
        return t;
    }

    // ---------- 工具方法 ----------

    public static Map<String, Object> sys(String text) {
        return msg("system", text);
    }

    public static Map<String, Object> userMsg(String text) {
        return msg("user", text);
    }

    public static Map<String, Object> assistantMsg(String text) {
        return msg("assistant", text);
    }

    private static Map<String, Object> msg(String role, String text) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("role", role);
        m.put("content", text);
        return m;
    }

    private void requireApiKey(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new LlmException("DeepSeek 密钥未配置：平台模式请让运维设置 DEEPSEEK_API_KEY，或在「模型调用方式」中填写你自己的 API Key");
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
