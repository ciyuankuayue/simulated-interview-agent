package com.bupt.mockinterview.agent;

import com.bupt.mockinterview.billing.QuotaExhaustedException;
import com.bupt.mockinterview.llm.LlmException;
import com.bupt.mockinterview.llm.LlmPort;
import com.bupt.mockinterview.llm.LlmProperties;
import com.bupt.mockinterview.security.CurrentUserService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Critic：面试结束时出复盘报告（domain.md §4.1 —— 讲解 / 纠正 / 知识补充 / 示范回答全归它）。
 *
 * 与实时侧的分工是硬边界：实时只追问或一句纠正，Critic 才展开讲。
 *
 * 降级：LLM 不可用时**不抛异常**，返回占位报告（overallNotes 写明原因），
 * 保证 `end` 一定能走完、会话一定能置 DONE。
 */
@Service
public class CriticService {

    private static final String SYSTEM = """
            你是资深 Java 后端面试教练。现在面试已结束，请基于全部轮次的问答出一份复盘报告。
            要求：
            1. 逐轮给出：讲解（这个知识点到底怎么回事）、纠正（答错/答偏在哪）、
               知识补充（该展开但没展开的）、示范回答（一段可直接背的回答，精炼不省关键）。
            2. 汇总答崩点（score<=3 的知识点）、表述问题（含精简答案指导）、整体注意事项、必要提醒。
            3. 给 JD 匹配度 matchScore（0-100 整数）。
            4. 只输出 JSON，不要 markdown 代码块，不要任何额外文字。格式：
            {"matchScore": 0到100,
             "perTurnReview": [{"turnSeq":1,"question":"","score":0到8,
                                "explanation":"","correction":"",
                                "knowledgeSupplement":"","exemplarAnswer":""}],
             "collapsedPoints": ["答崩的知识点"],
             "expressionIssues": [{"issue":"表述问题","guide":"怎么改"}],
             "overallNotes": "整体注意事项",
             "reminders": ["必要提醒"]}
            长度约束（务必遵守，否则 JSON 会被截断）：每轮 explanation≤100字、correction≤80字、
            knowledgeSupplement≤80字、exemplarAnswer≤120字；overallNotes≤200字；
            每轮 question 原样回填，不要改写或扩写。
            """;

    private final LlmPort llm;
    private final LlmProperties props;
    private final ObjectMapper mapper;
    private final CurrentUserService currentUser;

    public CriticService(LlmPort llm, LlmProperties props, ObjectMapper mapper, CurrentUserService currentUser) {
        this.llm = llm;
        this.props = props;
        this.mapper = mapper;
        this.currentUser = currentUser;
    }

    // ---------- 对外 ----------

    /** 一轮的输入摘要（避免 Critic 直接依赖 JPA 实体）。 */
    public record TurnBrief(Integer turnSeq, String question, String answer, Integer score) {}

    /** Critic 报告结构，对齐 api.md §3 report 响应与 critic_report 表字段。 */
    public record Payload(
            Integer matchScore,
            List<Map<String, Object>> perTurnReview,
            List<String> collapsedPoints,
            List<Map<String, Object>> expressionIssues,
            String overallNotes,
            List<String> reminders
    ) {}

    /** 生成报告；LLM 失败时返回占位报告，不抛。 */
    public Payload generate(Long sessionId, String jdText, List<TurnBrief> turns) {
        if (turns.isEmpty()) {
            return new Payload(null, List.of(), List.of(), List.of(), "本场没有有效问答，无法出报告。", List.of());
        }
        String user = buildUserPrompt(jdText, turns);
        try {
            String content = llm.chat(props.getModelReasoner(), true,
                    List.of(msg("system", SYSTEM), msg("user", user)),
                    null, true, props.getCriticMaxTokens()).content();
            return parse(content);
        } catch (QuotaExhaustedException | IllegalStateException e) {
            // 402 点数耗尽 / 409 BYOK 未配 key：业务决策，必须透传（end 接口映射 402/409），不许降级成占位报告
            throw e;
        } catch (LlmException e) {
            return new Payload(null, List.of(), List.of(), List.of(),
                    "Critic 报告生成失败（LLM 不可用）：" + e.getMessage(), List.of());
        } catch (Exception e) {
            return new Payload(null, List.of(), List.of(), List.of(),
                    "Critic 报告生成失败：" + e.getMessage(), List.of());
        }
    }

    // ---------- 内部 ----------

    private String buildUserPrompt(String jdText, List<TurnBrief> turns) {
        StringBuilder sb = new StringBuilder();
        sb.append("岗位 JD：\n").append(jdText == null ? "(无)" : jdText).append("\n\n全部问答：\n");
        for (TurnBrief t : turns) {
            sb.append("[turn ").append(t.turnSeq()).append(" | 得分 ").append(t.score()).append("]\n");
            sb.append("问：").append(t.question()).append("\n");
            sb.append("答：").append(t.answer() == null ? "(未作答)" : t.answer()).append("\n\n");
        }
        return sb.toString();
    }

    /** 解析 Critic 报告 JSON；模型输出被截断/不合法时尽力修复一次，仍失败才降级（不把原文塞进报告）。 */
    Payload parse(String content) {
        String json = stripCodeFence(content);
        try {
            return doParse(json);
        } catch (Exception first) {
            String repaired = repairTruncatedJson(json);
            if (repaired != null && !repaired.equals(json)) {
                try {
                    return doParse(repaired);
                } catch (Exception ignored) {
                    // 落到下方降级
                }
            }
            return new Payload(null, List.of(), List.of(), List.of(),
                    "Critic 报告 JSON 解析失败（模型输出被截断或不合法），请重试本场面试以重新生成报告。", List.of());
        }
    }

    private Payload doParse(String json) throws com.fasterxml.jackson.core.JsonProcessingException {
        JsonNode root = mapper.readTree(json);
        int match = root.path("matchScore").asInt(-1);
        Integer matchScore = match < 0 ? null : match;

        List<Map<String, Object>> perTurn = new ArrayList<>();
        JsonNode arr = root.path("perTurnReview");
        if (arr.isArray()) {
            for (JsonNode n : arr) {
                Map<String, Object> m = new java.util.LinkedHashMap<>();
                m.put("turnSeq", n.path("turnSeq").asInt(0));
                m.put("question", n.path("question").asText(""));
                m.put("score", n.path("score").asInt(0));
                m.put("explanation", n.path("explanation").asText(""));
                m.put("correction", n.path("correction").asText(""));
                m.put("knowledgeSupplement", n.path("knowledgeSupplement").asText(""));
                m.put("exemplarAnswer", n.path("exemplarAnswer").asText(""));
                perTurn.add(m);
            }
        }

        List<String> collapsed = textList(root.path("collapsedPoints"));

        List<Map<String, Object>> issues = new ArrayList<>();
        JsonNode iss = root.path("expressionIssues");
        if (iss.isArray()) {
            for (JsonNode n : iss) {
                Map<String, Object> m = new java.util.LinkedHashMap<>();
                m.put("issue", n.path("issue").asText(""));
                m.put("guide", n.path("guide").asText(""));
                issues.add(m);
            }
        }

        return new Payload(matchScore, perTurn, collapsed, issues,
                root.path("overallNotes").asText(""), textList(root.path("reminders")));
    }

    /** 组装一条 chat 消息（role + content）。 */
    private static Map<String, Object> msg(String role, String text) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("role", role);
        m.put("content", text);
        return m;
    }

    private static List<String> textList(JsonNode node) {
        List<String> out = new ArrayList<>();
        if (node != null && node.isArray()) {
            node.forEach(n -> out.add(n.asText("")));
        }
        return out;
    }

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

    /**
     * 尽力修复被 max_tokens 截断的 JSON：补未闭合的字符串引号 + 按「后开先闭」补未闭合的 ] / }。
     * 覆盖「值写到一半被砍」的常见场景；无法修复「键名中间被砍」的极端情况（靠抬高 max_tokens 规避）。
     */
    static String repairTruncatedJson(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        StringBuilder sb = new StringBuilder(s.trim());
        java.util.Deque<Character> opens = new java.util.ArrayDeque<>();
        boolean inStr = false, escaped = false;
        for (int i = 0; i < sb.length(); i++) {
            char c = sb.charAt(i);
            if (inStr) {
                if (escaped) { escaped = false; continue; }
                if (c == '\\') { escaped = true; continue; }
                if (c == '"') { inStr = false; }
                continue;
            }
            if (c == '"') { inStr = true; continue; }
            if (c == '{' || c == '[') {
                opens.push(c);
            } else if (c == '}' || c == ']') {
                if (!opens.isEmpty()) {
                    opens.pop(); // 成对消除
                }
            }
        }
        if (inStr) {
            sb.append('"'); // 末尾字符串未闭合
        }
        // 反向闭合：最后打开的括号先关，保证 ] 不越过外层 {
        while (!opens.isEmpty()) {
            sb.append(opens.pop() == '{' ? '}' : ']');
        }
        return sb.toString();
    }
}
