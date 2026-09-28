package com.bupt.mockinterview.web;

import com.bupt.mockinterview.agent.AgentEvent;
import com.bupt.mockinterview.agent.InterviewOrchestrator;
import com.bupt.mockinterview.domain.CriticReport;
import com.bupt.mockinterview.domain.InterviewSession;
import com.bupt.mockinterview.domain.repository.CriticReportRepository;
import com.bupt.mockinterview.domain.repository.InterviewSessionRepository;
import com.bupt.mockinterview.domain.repository.InterviewTurnRepository;
import com.bupt.mockinterview.domain.repository.ResumeVersionRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

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

    public InterviewController(InterviewOrchestrator orchestrator, SseBroker broker,
                               InterviewSessionRepository sessionRepo, InterviewTurnRepository turnRepo,
                               CriticReportRepository reportRepo, ResumeVersionRepository resumeRepo,
                               ObjectMapper mapper) {
        this.orchestrator = orchestrator;
        this.broker = broker;
        this.sessionRepo = sessionRepo;
        this.turnRepo = turnRepo;
        this.reportRepo = reportRepo;
        this.resumeRepo = resumeRepo;
        this.mapper = mapper;
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
        if (req.resumeId() != null && !resumeRepo.existsById(req.resumeId())) {
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
     * 最近 20 场会话（精简卡片），供历史报告回看入口。
     * hasReport=false 的场次（进行中/未 end）不可点开报告。
     */
    @GetMapping("/sessions")
    public List<Map<String, Object>> sessions() {
        List<InterviewSession> sessions =
                sessionRepo.findAllByOrderByIdDesc(org.springframework.data.domain.PageRequest.of(0, 20));
        List<Long> ids = sessions.stream().map(InterviewSession::getId).toList();
        java.util.Set<Long> reportedIds = reportRepo.findBySessionIdIn(ids).stream()
                .map(r -> r.getSession() == null ? null : r.getSession().getId())
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toSet());

        List<Map<String, Object>> resp = new ArrayList<>();
        for (InterviewSession s : sessions) {
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

    // ---------- answer ----------

    public record AnswerRequest(String answer) {}

    @PostMapping("/{sessionId}/answer")
    public Map<String, Object> answer(@PathVariable Long sessionId, @RequestBody AnswerRequest req) {
        String text = req == null ? null : req.answer();
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("answer 不能为空");
        }
        List<AgentEvent> events = orchestrator.answer(sessionId, text);
        broker.publish(sessionId, events);
        return Map.of("accepted", true);
    }

    // ---------- SSE ----------

    @GetMapping(path = "/{sessionId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@PathVariable Long sessionId) {
        if (!sessionRepo.existsById(sessionId)) {
            throw new IllegalArgumentException("会话不存在: " + sessionId);
        }
        return broker.subscribe(sessionId);
    }

    // ---------- end ----------

    /** 主动结束：触发 Critic 出报告。已结束时幂等返回既有报告。 */
    @PostMapping("/{sessionId}/end")
    public Map<String, Object> end(@PathVariable Long sessionId) {
        InterviewSession session = sessionRepo.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("会话不存在: " + sessionId));
        CriticReport existing = reportRepo.findBySessionId(sessionId);
        if (existing != null) {
            return reportView(existing);
        }
        List<AgentEvent> events = orchestrator.end(sessionId);
        broker.publish(sessionId, events);
        CriticReport report = reportRepo.findBySessionId(sessionId);
        if (report == null) {
            throw new IllegalStateException("报告生成失败，会话=" + sessionId);
        }
        return reportView(report);
    }

    // ---------- report / status ----------

    @GetMapping("/{sessionId}/report")
    public Map<String, Object> report(@PathVariable Long sessionId) {
        CriticReport report = reportRepo.findBySessionId(sessionId);
        if (report == null) {
            throw new IllegalArgumentException("该会话还没有报告，请先调用 end");
        }
        return reportView(report);
    }

    @GetMapping("/{sessionId}/status")
    public Map<String, Object> status(@PathVariable Long sessionId) {
        InterviewSession session = sessionRepo.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("会话不存在: " + sessionId));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sessionId", session.getId());
        body.put("status", session.getStatus() == null ? null : session.getStatus().name());
        body.put("roles", parseRoles(session.getRoles()));
        body.put("turnCount", turnRepo.findBySessionIdOrderBySeqAsc(sessionId).size());
        body.put("matchScore", session.getMatchScore());
        return body;
    }

    // ---------- 内部 ----------

    private String statusOf(Long sessionId) {
        return sessionRepo.findById(sessionId)
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
        body.put("createdAt", r.getCreatedAt() == null ? null : r.getCreatedAt().toString());
        return body;
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
}
