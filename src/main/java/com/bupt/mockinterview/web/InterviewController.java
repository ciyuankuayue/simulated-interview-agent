package com.bupt.mockinterview.web;

import com.bupt.mockinterview.agent.AgentEvent;
import com.bupt.mockinterview.agent.InterviewOrchestrator;
import com.bupt.mockinterview.agent.InterviewRequestGuard;
import com.bupt.mockinterview.domain.CriticReport;
import com.bupt.mockinterview.domain.InterviewSession;
import com.bupt.mockinterview.domain.InterviewTurn;
import com.bupt.mockinterview.domain.User;
import com.bupt.mockinterview.domain.repository.CriticReportRepository;
import com.bupt.mockinterview.domain.repository.InterviewSessionRepository;
import com.bupt.mockinterview.domain.repository.InterviewTurnRepository;
import com.bupt.mockinterview.domain.repository.ResumeVersionRepository;
import com.bupt.mockinterview.security.CurrentUserService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 模拟面试主流程（api.md §3 + §4）：start / answer / SSE events / end / report / status。
 *
 * 分层原则：**本 controller 只做协议适配，不含编排逻辑**——
 * 事件全部由 T6 的 {@link InterviewOrchestrator} 生产，这里负责「受理请求 → 推事件 → 组装响应」。
 *
 * 关于 answer 的同步性：api.md §3 规定 answer 只回 `{accepted:true}`、结果走 SSE，
 * 所以这里同步跑完判分再返回（判分几十秒内，前端已挂在 SSE 上能实时收到事件）。
 * 决策已砍掉 MQ，异步化留到规模上来再做。
 */
@RestController
@Validated
@RequestMapping("/api/interview")
public class InterviewController {

    private final InterviewOrchestrator orchestrator;
    private final SseBroker broker;
    private final InterviewSessionRepository sessionRepo;
    private final InterviewTurnRepository turnRepo;
    private final CriticReportRepository reportRepo;
    private final ResumeVersionRepository resumeRepo;
    private final ObjectMapper mapper;
    private final CurrentUserService currentUser;
    private final InterviewRequestGuard requestGuard;

    public InterviewController(InterviewOrchestrator orchestrator, SseBroker broker,
                               InterviewSessionRepository sessionRepo, InterviewTurnRepository turnRepo,
                               CriticReportRepository reportRepo, ResumeVersionRepository resumeRepo,
                               ObjectMapper mapper, CurrentUserService currentUser, InterviewRequestGuard requestGuard) {
        this.orchestrator = orchestrator;
        this.broker = broker;
        this.sessionRepo = sessionRepo;
        this.turnRepo = turnRepo;
        this.reportRepo = reportRepo;
        this.resumeRepo = resumeRepo;
        this.mapper = mapper;
        this.currentUser = currentUser;
        this.requestGuard = requestGuard;
    }

    // ---------- start ----------

    public record StartRequest(Long jdId, List<String> roles, Long resumeId,
                               @Min(value = 1, message = "maxQuestions 最小为 1")
                               @Max(value = InterviewOrchestrator.MAX_MAIN_QUESTIONS,
                                       message = "主问题数不能超过 " + InterviewOrchestrator.MAX_MAIN_QUESTIONS)
                               Integer maxQuestions) {}

    @PostMapping("/start")
    public Map<String, Object> start(@Valid @RequestBody StartRequest req) {
        if (req == null || req.jdId() == null) {
            throw new IllegalArgumentException("jdId 不能为空");
        }
        if (req.resumeId() != null && !resumeRepo.existsByIdAndUserId(req.resumeId(), currentUser.currentUserId())) {
            throw new IllegalArgumentException("简历版本不存在: " + req.resumeId());
        }
        InterviewOrchestrator.StartResult r =
                orchestrator.start(req.jdId(), req.roles(), req.maxQuestions(), req.resumeId());
        // 首题事件先入 buffer：前端拿到 sessionId 后才来连 SSE，靠重放补齐
        broker.publish(r.sessionId(), r.events());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sessionId", r.sessionId());
        body.put("firstQuestion", r.firstQuestion());
        body.put("status", statusOf(r.sessionId()));
        return body;
    }

    // ---------- 历史会话列表（T10） ----------

    /**
     * 历史面试列表（F8 支持筛选）。
     *
     * @param jobType     岗位类型精确匹配（可选）
     * @param sameTypeTag 同类标签精确匹配（可选）
     * @param status      会话状态（DONE/INTERVIEWING/...，可选）
     * @param minScore    匹配度下限（含，可选）
     * @param maxScore    匹配度上限（含，可选）
     * @param keyword     在 JD 原文里模糊搜索（可选，大小写不敏感）
     * @param from        起始时间 ISO-8601（含，可选）
     * @param to          结束时间 ISO-8601（含，可选）
     */
    @GetMapping("/sessions")
    public List<Map<String, Object>> sessions(
            @RequestParam(required = false) String jobType,
            @RequestParam(required = false) String sameTypeTag,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Integer minScore,
            @RequestParam(required = false) Integer maxScore,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        User user = currentUser.currentUser();
        List<InterviewSession> sessions = sessionRepo.findByUserOrderByIdDesc(user);
        List<Long> ids = sessions.stream().map(InterviewSession::getId).toList();
        java.util.Set<Long> reportedIds = reportRepo.findByUser(user).stream()
                .map(r -> r.getSession() == null ? null : r.getSession().getId())
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toSet());

        Instant fromAt = parseInstant(from);
        Instant toAt = parseInstant(to);
        String kw = keyword == null ? null : keyword.trim().toLowerCase();

        List<Map<String, Object>> resp = new ArrayList<>();
        for (InterviewSession s : sessions) {
            // 过滤
            if (jobType != null && !jobType.isBlank()) {
                String jt = s.getJdProfile() == null ? null : s.getJdProfile().getJobType();
                if (!jobType.equals(jt)) continue;
            }
            if (sameTypeTag != null && !sameTypeTag.isBlank()) {
                String st = s.getJdProfile() == null ? null : s.getJdProfile().getSameTypeTag();
                if (!sameTypeTag.equals(st)) continue;
            }
            if (status != null && !status.isBlank()) {
                String st = s.getStatus() == null ? null : s.getStatus().name();
                if (!status.equalsIgnoreCase(st)) continue;
            }
            if (minScore != null && (s.getMatchScore() == null || s.getMatchScore() < minScore)) continue;
            if (maxScore != null && (s.getMatchScore() == null || s.getMatchScore() > maxScore)) continue;
            if (fromAt != null && (s.getCreatedAt() == null || s.getCreatedAt().isBefore(fromAt))) continue;
            if (toAt != null && (s.getCreatedAt() == null || s.getCreatedAt().isAfter(toAt))) continue;
            if (kw != null && !kw.isEmpty()) {
                String raw = s.getJdProfile() == null ? null : s.getJdProfile().getRawText();
                if (raw == null || !raw.toLowerCase().contains(kw)) continue;
            }

            Map<String, Object> m = new LinkedHashMap<>();
            m.put("sessionId", s.getId());
            m.put("jdId", s.getJdProfile() == null ? null : s.getJdProfile().getId());
            m.put("jobType", s.getJdProfile() == null ? null : s.getJdProfile().getJobType());
            m.put("sameTypeTag", s.getJdProfile() == null ? null : s.getJdProfile().getSameTypeTag());
            m.put("roles", parseRoles(s.getRoles()));
            m.put("status", s.getStatus() == null ? null : s.getStatus().name());
            m.put("matchScore", s.getMatchScore());
            m.put("maxQuestions", s.getMaxQuestions());
            m.put("hasReport", reportedIds.contains(s.getId()));
            m.put("createdAt", s.getCreatedAt() == null ? null : s.getCreatedAt().toString());
            resp.add(m);
        }
        return resp;
    }

    private static Instant parseInstant(String s) {
        if (s == null || s.isBlank()) return null;
        try { return Instant.parse(s); } catch (Exception e) { return null; }
    }

    // ---------- answer ----------

    public record AnswerRequest(String answer) {}

    @PostMapping("/{sessionId}/answer")
    public Map<String, Object> answer(@PathVariable Long sessionId, @RequestBody AnswerRequest req) {
        String text = req == null ? null : req.answer();
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("answer 不能为空");
        }
        if (!sessionRepo.findByIdAndUserId(sessionId, currentUser.currentUserId()).isPresent()) {
            throw new IllegalArgumentException("会话不存在: " + sessionId);
        }
        return requestGuard.execute(currentUser.currentUserId(), sessionId, () -> {
            List<AgentEvent> events = orchestrator.answer(sessionId, text);
            broker.publish(sessionId, events);
            return Map.of("accepted", true);
        });
    }

    // ---------- SSE ----------

    @GetMapping(path = "/{sessionId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@PathVariable Long sessionId) {
        if (!sessionRepo.findByIdAndUserId(sessionId, currentUser.currentUserId()).isPresent()) {
            throw new IllegalArgumentException("会话不存在: " + sessionId);
        }
        return broker.subscribe(sessionId);
    }

    // ---------- end ----------

    /** 主动结束：触发 Critic 出报告。已结束时幂等返回既有报告。 */
    @PostMapping("/{sessionId}/end")
    public Map<String, Object> end(@PathVariable Long sessionId) {
        return requestGuard.execute(currentUser.currentUserId(), sessionId, () -> endGuarded(sessionId));
    }

    private Map<String, Object> endGuarded(Long sessionId) {
        User user = currentUser.currentUser();
        InterviewSession session = sessionRepo.findByIdAndUserId(sessionId, currentUser.currentUserId())
                .orElseThrow(() -> new IllegalArgumentException("会话不存在: " + sessionId));
        CriticReport existing = reportRepo.findBySessionIdAndUser(sessionId, user).orElse(null);
        if (existing != null) {
            return reportView(existing);
        }
        List<AgentEvent> events = orchestrator.end(sessionId);
        broker.publish(sessionId, events);
        CriticReport report = reportRepo.findBySessionIdAndUser(sessionId, user).orElse(null);
        if (report == null) {
            throw new IllegalStateException("报告生成失败，会话=" + sessionId);
        }
        return reportView(report);
    }

    // ---------- report / status ----------

    @GetMapping("/{sessionId}/report")
    public Map<String, Object> report(@PathVariable Long sessionId) {
        CriticReport report = reportRepo.findBySessionIdAndUser(sessionId, currentUser.currentUser()).orElse(null);
        if (report == null) {
            throw new IllegalArgumentException("该会话还没有报告，请先调用 end");
        }
        return reportView(report);
    }

    @GetMapping("/{sessionId}/status")
    public Map<String, Object> status(@PathVariable Long sessionId) {
        InterviewSession session = sessionRepo.findByIdAndUserId(sessionId, currentUser.currentUserId())
                .orElseThrow(() -> new IllegalArgumentException("会话不存在: " + sessionId));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sessionId", session.getId());
        body.put("status", session.getStatus() == null ? null : session.getStatus().name());
        body.put("roles", parseRoles(session.getRoles()));
        body.put("turnCount", turnRepo.findBySessionIdOrderBySeqAsc(sessionId).size());
        body.put("matchScore", session.getMatchScore());
        return body;
    }

    // ---------- F7 分数趋势图 ----------

    /**
     * 多场面试的匹配度趋势（按时间升序），供前端画折线图。
     * 只返回有 matchScore 的场次；每场含 matchScore + 报告里的逐轮均分 + 行为维度（若有）。
     */
    @GetMapping("/trend")
    public List<Map<String, Object>> trend() {
        User user = currentUser.currentUser();
        List<Map<String, Object>> resp = new ArrayList<>();
        for (InterviewSession s : sessionRepo.findByUserOrderByIdDesc(user)) {
            if (s.getMatchScore() == null) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("sessionId", s.getId());
            m.put("createdAt", s.getCreatedAt() == null ? null : s.getCreatedAt().toString());
            m.put("matchScore", s.getMatchScore());
            m.put("jobType", s.getJdProfile() == null ? null : s.getJdProfile().getJobType());

            CriticReport r = reportRepo.findBySessionIdAndUser(s.getId(), user).orElse(null);
            if (r != null) {
                Object turnsObj = parseJson(r.getPerTurnReview(), new TypeReference<List<Map<String, Object>>>() {});
                if (turnsObj instanceof List<?> turns && !turns.isEmpty()) {
                    double sum = 0; int cnt = 0;
                    for (Object o : turns) {
                        if (!(o instanceof Map<?, ?> t)) continue;
                        Object sc = t.get("score");
                        if (sc != null) { try { sum += Double.parseDouble(String.valueOf(sc)); cnt++; } catch (Exception ignored) {} }
                    }
                    m.put("avgTurnScore", cnt > 0 ? Math.round(sum / cnt * 10) / 10.0 : null);
                }
                Map<String, Object> br = parseJsonObjectOrNull(r.getBehaviorReview());
                if (br != null && br.get("dimensions") instanceof Map<?, ?> dims) {
                    Map<String, Object> dimMap = new LinkedHashMap<>();
                    for (Map.Entry<?, ?> e : dims.entrySet()) {
                        dimMap.put(String.valueOf(e.getKey()), e.getValue());
                    }
                    m.put("behaviorDimensions", dimMap);
                }
            }
            resp.add(m);
        }
        resp.sort((a, b) -> {
            String ta = (String) a.get("createdAt");
            String tb = (String) b.get("createdAt");
            if (ta == null) return -1;
            if (tb == null) return 1;
            return ta.compareTo(tb);
        });
        return resp;
    }

    // ---------- 逐轮回看（F2） ----------

    /**
     * 某场面试的全部问答轮次（按 seq 升序），供历史回看时逐轮展示原题与原答。
     * scoreJson 不回传（内部判分细节，报告里已有 perTurnReview）。
     */
    @GetMapping("/{sessionId}/turns")
    public List<Map<String, Object>> turns(@PathVariable Long sessionId) {
        sessionRepo.findByIdAndUserId(sessionId, currentUser.currentUserId())
                .orElseThrow(() -> new IllegalArgumentException("会话不存在: " + sessionId));
        List<Map<String, Object>> resp = new ArrayList<>();
        for (InterviewTurn t : turnRepo.findBySessionIdOrderBySeqAsc(sessionId)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("seq", t.getSeq());
            m.put("layer", t.getLayer());
            m.put("question", t.getQuestion());
            m.put("answer", t.getAnswer());
            resp.add(m);
        }
        return resp;
    }

    // ---------- 报告导出 Markdown（F4） ----------

    /**
     * 把 Critic 报告导出为 Markdown 文本，前端点击「导出 MD」直接下载。
     * 纯文本拼接，零依赖；老报告字段缺失时跳过对应小节。
     */
    @GetMapping(value = "/{sessionId}/report.md", produces = "text/markdown; charset=UTF-8")
    public ResponseEntity<String> reportMarkdown(@PathVariable Long sessionId) {
        CriticReport r = reportRepo.findBySessionIdAndUser(sessionId, currentUser.currentUser()).orElse(null);
        if (r == null) {
            throw new IllegalArgumentException("该会话还没有报告，请先调用 end");
        }
        StringBuilder md = new StringBuilder();
        md.append("# AI 模拟面试报告\n\n");
        md.append("- 匹配度：**").append(r.getMatchScore() == null ? "—" : r.getMatchScore()).append("** / 100\n");
        if (r.getCreatedAt() != null) {
            md.append("- 生成时间：").append(r.getCreatedAt()).append("\n");
        }
        md.append("\n");

        // 逐轮讲解
        Object turnsObj = parseJson(r.getPerTurnReview(), new TypeReference<List<Map<String, Object>>>() {});
        if (turnsObj instanceof List<?> turns && !turns.isEmpty()) {
            md.append("## 逐轮讲解\n\n");
            for (Object o : turns) {
                if (!(o instanceof Map<?, ?> tRaw)) continue;
                @SuppressWarnings("unchecked")
                Map<String, Object> t = (Map<String, Object>) tRaw;
                md.append("### 第 ").append(t.get("turnSeq")).append(" 轮 · ")
                        .append(t.get("score")).append("/8");
                if ("behavior".equals(t.get("track"))) md.append(" · 行为");
                md.append("\n\n");
                md.append("**题目**：").append(str(t.get("question"))).append("\n\n");
                appendMdField(md, "讲解", t.get("explanation"));
                appendMdField(md, "纠正", t.get("correction"));
                appendMdField(md, "知识补充", t.get("knowledgeSupplement"));
                appendMdField(md, "示范回答", t.get("exemplarAnswer"));
            }
        }

        // 答崩点
        Object collapsedObj = parseJson(r.getCollapsedPoints(), new TypeReference<List<String>>() {});
        if (collapsedObj instanceof List<?> collapsed && !collapsed.isEmpty()) {
            md.append("## 答崩点\n\n");
            for (Object p : collapsed) md.append("- ").append(p).append("\n");
            md.append("\n");
        }

        // 表述问题
        Object issuesObj = parseJson(r.getExpressionIssues(), new TypeReference<List<Map<String, Object>>>() {});
        if (issuesObj instanceof List<?> issues && !issues.isEmpty()) {
            md.append("## 表述问题\n\n");
            for (Object o : issues) {
                if (!(o instanceof Map<?, ?> iRaw)) continue;
                md.append("- **").append(str(iRaw.get("issue"))).append("**：")
                        .append(str(iRaw.get("guide"))).append("\n");
            }
            md.append("\n");
        }

        // 行为维度
        Map<String, Object> br = parseJsonObjectOrNull(r.getBehaviorReview());
        if (br != null) {
            md.append("## 行为面试评估\n\n");
            Object dims = br.get("dimensions");
            if (dims instanceof Map) {
                md.append("| 维度 | 评分 |\n|---|---|\n");
                for (Map.Entry<String, Object> e : ((Map<String, Object>) dims).entrySet()) {
                    md.append("| ").append(e.getKey()).append(" | ").append(e.getValue()).append("/2 |\n");
                }
                md.append("\n");
            }
            if (br.get("summary") != null) {
                md.append(br.get("summary")).append("\n\n");
            }
            Object sugs = br.get("suggestions");
            if (sugs instanceof List) {
                for (Object s : (List<?>) sugs) md.append("- ").append(s).append("\n");
                md.append("\n");
            }
        }

        // 整体注意事项
        if (r.getOverallNotes() != null && !r.getOverallNotes().isBlank()) {
            md.append("## 整体注意事项\n\n").append(r.getOverallNotes()).append("\n\n");
        }

        // 必要提醒
        Object remindersObj = parseJson(r.getReminders(), new TypeReference<List<String>>() {});
        if (remindersObj instanceof List<?> reminders && !reminders.isEmpty()) {
            md.append("## 必要提醒\n\n");
            for (Object p : reminders) md.append("- ").append(p).append("\n");
        }

        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=\"interview-" + sessionId + "-report.md\"")
                .body(md.toString());
    }

    // ---------- 内部 ----------

    private String statusOf(Long sessionId) {
        return sessionRepo.findByIdAndUserId(sessionId, currentUser.currentUserId())
                .map(s -> s.getStatus() == null ? null : s.getStatus().name())
                .orElse(null);
    }

    /** 报告视图：把表里存成 TEXT 的 JSON 还原成结构化对象（api.md §3 report 响应）。 */
    private Map<String, Object> reportView(CriticReport r) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("reportId", r.getId());
        body.put("matchScore", r.getMatchScore());
        body.put("perTurnReview", parseJson(r.getPerTurnReview(), new TypeReference<List<Map<String, Object>>>() {}));
        body.put("collapsedPoints", parseJson(r.getCollapsedPoints(), new TypeReference<List<String>>() {}));
        body.put("expressionIssues", parseJson(r.getExpressionIssues(), new TypeReference<List<Map<String, Object>>>() {}));
        body.put("overallNotes", r.getOverallNotes());
        body.put("reminders", parseJson(r.getReminders(), new TypeReference<List<String>>() {}));
        // R7：行为维度（纯技术场/老报告列为 NULL → null，前端整卡不渲染；解析失败同样给 null）
        body.put("behaviorReview", parseJsonObjectOrNull(r.getBehaviorReview()));
        body.put("createdAt", r.getCreatedAt() == null ? null : r.getCreatedAt().toString());
        return body;
    }

    /** R7：TEXT JSON → Map；空/NULL/解析失败一律 null（行为维度卡整体缺省，不影响其余报告字段）。 */
    private Map<String, Object> parseJsonObjectOrNull(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return mapper.readValue(text, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return null;
        }
    }

    private <T> Object parseJson(String text, TypeReference<T> type) {
        if (text == null || text.isBlank()) {
            return new ArrayList<>();
        }
        try {
            return mapper.readValue(text, type);
        } catch (Exception e) {
            // 解析不出就原样给字符串，至少不丢内容
            return text;
        }
    }

    private List<String> parseRoles(String rolesJson) {
        if (rolesJson == null || rolesJson.isBlank()) {
            return List.of();
        }
        try {
            return mapper.readValue(rolesJson, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    private static String str(Object v) {
        return v == null ? "" : String.valueOf(v);
    }

    /** Markdown 导出用：字段非空才输出「- 标签：值」一行。 */
    private static void appendMdField(StringBuilder md, String label, Object value) {
        if (value == null || String.valueOf(value).isBlank()) return;
        md.append("- **").append(label).append("**：").append(value).append("\n");
    }
}
