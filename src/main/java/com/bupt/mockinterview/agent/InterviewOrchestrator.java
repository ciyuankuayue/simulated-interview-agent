package com.bupt.mockinterview.agent;

import com.bupt.mockinterview.agent.CriticService.Payload;
import com.bupt.mockinterview.agent.CriticService.TurnBrief;
import com.bupt.mockinterview.billing.QuotaExhaustedException;
import com.bupt.mockinterview.domain.CriticReport;
import com.bupt.mockinterview.domain.InterviewSession;
import com.bupt.mockinterview.domain.InterviewTurn;
import com.bupt.mockinterview.domain.JdProfile;
import com.bupt.mockinterview.domain.SessionStatus;
import com.bupt.mockinterview.domain.User;
import com.bupt.mockinterview.domain.repository.CriticReportRepository;
import com.bupt.mockinterview.domain.repository.InterviewSessionRepository;
import com.bupt.mockinterview.domain.repository.InterviewTurnRepository;
import com.bupt.mockinterview.domain.repository.JdProfileRepository;
import com.bupt.mockinterview.llm.LlmPort;
import com.bupt.mockinterview.llm.ScoreResult;
import com.bupt.mockinterview.memory.WeakPointMemoryService;
import com.bupt.mockinterview.rag.RagService;
import com.bupt.mockinterview.security.CurrentUserService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 面试编排（T6 核心）：手写状态机，不引编排框架（MVP 阶段框架是负担）。
 *
 * 状态机（对应 api.md §4 事件流示例）：
 * <pre>
 * start  → question(layer0) → await_answer
 * answer → score → [followup(layer+1) | question(下一道, layer0)] → await_answer
 * end    → report → done
 * </pre>
 *
 * 三条硬约束：
 * 1. **追问封顶 2 层**（domain.md §4）：8=S 不追问｜6-7=A 1 层｜≤5=B/C/D 2 层。
 * 2. **实时只追问或一句纠正**，讲解/示范归 Critic（domain.md §4.1）。
 * 3. **LLM 失败降级**：判分失败→跳过追问直接下一题；追问生成失败→直接下一题；
 *    Critic 失败→占位报告。任何单点失败都不让整场面试中断。
 */
@Service
public class InterviewOrchestrator {

    /** 主问题上限（不含追问），防止无限面下去。 */
    public static final int MAX_MAIN_QUESTIONS = 8;
    private static final int RAG_TOP_K = 5;

    private final LlmPort llm;
    private final RagService rag;
    private final CriticService critic;
    private final WeakPointMemoryService memory;
    private final ObjectMapper mapper;
    private final JdProfileRepository jdRepo;
    private final InterviewSessionRepository sessionRepo;
    private final InterviewTurnRepository turnRepo;
    private final CriticReportRepository reportRepo;
    private final CurrentUserService currentUser;

    public InterviewOrchestrator(LlmPort llm, RagService rag, CriticService critic, WeakPointMemoryService memory,
                                 ObjectMapper mapper, JdProfileRepository jdRepo,
                                 InterviewSessionRepository sessionRepo,
                                 InterviewTurnRepository turnRepo, CriticReportRepository reportRepo,
                                 CurrentUserService currentUser) {
        this.llm = llm;
        this.rag = rag;
        this.critic = critic;
        this.memory = memory;
        this.mapper = mapper;
        this.jdRepo = jdRepo;
        this.sessionRepo = sessionRepo;
        this.turnRepo = turnRepo;
        this.reportRepo = reportRepo;
        this.currentUser = currentUser;
    }

    public record StartResult(Long sessionId, String firstQuestion, List<AgentEvent> events) {}

    // ---------- start ----------

    /** 开启一场模拟（默认主问题上限 {@link #MAX_MAIN_QUESTIONS}）。 */
    @Transactional
    public StartResult start(Long jdId, List<String> roleNames) {
        return start(jdId, roleNames, null, null);
    }

    @Transactional
    public StartResult start(Long jdId, List<String> roleNames, Integer maxQuestions) {
        return start(jdId, roleNames, maxQuestions, null);
    }

    /**
     * 开启一场模拟：解析 JD + 检索资料 → 出首题（第一个角色先问）。
     *
     * @param maxQuestions 主问题上限（不含追问），null 或越界时取默认 {@link #MAX_MAIN_QUESTIONS}，
     *                     合法区间 [1, {@link #MAX_MAIN_QUESTIONS}]
     * @param resumeId     用户下拉选择的简历版本 id（仅留痕，null=未指定；不做语料隔离）
     */
    @Transactional
    public StartResult start(Long jdId, List<String> roleNames, Integer maxQuestions, Long resumeId) {
        JdProfile jd = jdRepo.findByIdAndUserId(jdId, currentUser.currentUserId())
                .orElseThrow(() -> new IllegalArgumentException("JD 不存在: " + jdId));

        List<Role> roles = resolveRoles(roleNames);

        InterviewSession session = new InterviewSession();
        session.setUser(currentUser.currentUser());
        session.setJdProfile(jd);
        session.setRoles(Role.toJson(roles, mapper));
        session.setStatus(SessionStatus.QUESTIONING);
        session.setMaxQuestions(normalizeMaxQuestions(maxQuestions));
        session.setResumeId(resumeId);
        session = sessionRepo.save(session);

        List<String> refs = new ArrayList<>();
        String context = retrieveContext(jd.getRawText(), refs);

        String firstQuestion = llm.followUp(roles.get(0).systemPrompt(),
                "岗位 JD：\n" + nullToEmpty(jd.getRawText())
                        + "\n\n候选人的资料片段（可能为空）：\n" + context
                        + "\n\n请出第一道面试题。要求：紧扣 JD 考点，一句话，只输出题目本身。").content();

        InterviewTurn first = newTurn(session, 1, 0, firstQuestion, toJson(refs));

        List<AgentEvent> events = List.of(
                AgentEvent.of(AgentEvent.QUESTION, "turnSeq", first.getSeq(), "question", firstQuestion,
                        "role", roles.get(0).displayName(), "layer", 0),
                AgentEvent.of(AgentEvent.AWAIT_ANSWER, "turnSeq", first.getSeq()));
        return new StartResult(session.getId(), firstQuestion, events);
    }

    // ---------- answer ----------

    /** 提交回答：判分 → 决定追问还是下一题。 */
    @Transactional
    public List<AgentEvent> answer(Long sessionId, String answer) {
        InterviewSession session = sessionRepo.findByIdAndUserId(sessionId, currentUser.currentUserId())
                .orElseThrow(() -> new IllegalArgumentException("会话不存在: " + sessionId));
        if (session.getStatus() != SessionStatus.QUESTIONING) {
            throw new IllegalStateException("会话不在答题中，当前状态=" + session.getStatus());
        }
        List<InterviewTurn> turns = turnRepo.findBySessionIdOrderBySeqAsc(sessionId);
        if (turns.isEmpty()) {
            throw new IllegalStateException("会话还没有题目");
        }
        InterviewTurn current = turns.get(turns.size() - 1);
        if (current.getAnswer() != null && !current.getAnswer().isBlank()) {
            throw new IllegalStateException("本轮已作答，请等待下一题");
        }

        List<AgentEvent> events = new ArrayList<>();
        long t0 = System.currentTimeMillis();

        ScoreResult sr = null;
        try {
            sr = llm.score(current.getQuestion(), answer);
            current.setScoreJson(mapper.writeValueAsString(scoreJsonOf(sr)));
        } catch (QuotaExhaustedException | IllegalStateException e) {
            // 402 点数耗尽 / 409 BYOK 未配 key：业务决策，直接打断面试透传给前端，不许降级吞掉
            throw e;
        } catch (Exception e) {
            // 降级：判分失败不中断，跳过追问直接下一题
            current.setScoreJson("{\"error\":\"判分失败: " + e.getMessage().replace("\"", "'") + "\"}");
            events.add(AgentEvent.of(AgentEvent.ERROR, "code", "SCORE_FAILED", "message", e.getMessage()));
        }
        current.setAnswer(answer);
        current.setElapsedMs(System.currentTimeMillis() - t0);
        turnRepo.save(current);

        events.add(AgentEvent.of(AgentEvent.SCORE, "turnSeq", current.getSeq(),
                "score", sr == null ? null : sr.score(),
                "oneLineCorrection", oneLineCorrection(sr)));

        // T8 memory：判分结果入错题本（≤3 记/升阶，≥6 出队）。写失败只告警，不让面试中断
        if (sr != null) {
            try {
                memory.onTurnScored(session.getJdProfile(), topicOf(sr, current.getQuestion()), sr.score(),
                        statusOf(sr), Instant.now(), session.getId(), current.getSeq());
            } catch (Exception e) {
                events.add(AgentEvent.of(AgentEvent.ERROR, "code", "WEAK_POINT_FAILED", "message", e.getMessage()));
            }
        }

        // 追问决策：已追问层数 < 目标层数（followupDepth 已封顶 2），且未满分
        int askedLayer = current.getLayer() == null ? 0 : current.getLayer();
        int target = sr == null ? 0 : sr.followupDepth();
        if (sr != null && sr.score() < 8 && askedLayer < target) {
            String followup = sr.followup();
            if (followup == null || followup.isBlank()) {
                try {
                    followup = llm.followUp(currentRole(session, turns).systemPrompt(),
                            "题目：" + current.getQuestion() + "\n候选人的回答：" + answer
                                    + "\n请就这个回答再追一层 why（一句话，只输出追问本身）").content();
                } catch (QuotaExhaustedException | IllegalStateException e) {
                    throw e;
                } catch (Exception e) {
                    followup = null;
                    events.add(AgentEvent.of(AgentEvent.ERROR, "code", "FOLLOWUP_FAILED", "message", e.getMessage()));
                }
            }
            if (followup != null && !followup.isBlank()) {
                InterviewTurn next = newTurn(session, current.getSeq() + 1, askedLayer + 1, followup, null);
                events.add(AgentEvent.of(AgentEvent.FOLLOWUP, "turnSeq", next.getSeq(),
                        "question", followup, "layer", next.getLayer()));
                events.add(AgentEvent.of(AgentEvent.AWAIT_ANSWER, "turnSeq", next.getSeq()));
                return events;
            }
        }
        return nextMainQuestion(session, turns, events);
    }

    /** 本轮追问/纠正所属的角色（继承最近一道主问题的角色）。 */
    private Role currentRole(InterviewSession session, List<InterviewTurn> turns) {
        List<Role> roles = Role.parse(session.getRoles(), mapper);
        long mainCount = turns.stream().filter(t -> Integer.valueOf(0).equals(t.getLayer())).count();
        int idx = (int) ((mainCount - 1 + roles.size()) % roles.size());
        return roles.get(idx);
    }

    // ---------- 下一道主问题 ----------

    private List<AgentEvent> nextMainQuestion(InterviewSession session, List<InterviewTurn> turns,
                                              List<AgentEvent> events) {
        long mainCount = turns.stream().filter(t -> Integer.valueOf(0).equals(t.getLayer())).count();
        int limit = normalizeMaxQuestions(session.getMaxQuestions());
        if (mainCount >= limit) {
            // 自然结束（答完最后一道主问题）：自动出 Critic 报告再 done，用户无需再点「结束」。
            // events 里已含本轮 score；finish 产出 report + done（带达上限原因）。
            events.addAll(finish(session, "MAX_QUESTIONS_REACHED"));
            return events;
        }
        List<Role> roles = Role.parse(session.getRoles(), mapper);
        Role nextRole = roles.get((int) (mainCount % roles.size()));

        StringBuilder asked = new StringBuilder();
        for (InterviewTurn t : turns) {
            asked.append("- ").append(t.getQuestion()).append("\n");
        }
        String jdText = session.getJdProfile() == null ? "" : nullToEmpty(session.getJdProfile().getRawText());

        String question;
        try {
            question = llm.followUp(nextRole.systemPrompt(),
                    "岗位 JD：\n" + jdText + "\n\n已经问过的问题（不要重复）：\n" + asked
                            + "\n\n请出一道新的面试题，一句话，只输出题目本身。").content();
        } catch (QuotaExhaustedException | IllegalStateException e) {
            // 402/409：不降级为 QUESTION_FAILED，直接打断（start/answer HTTP 由全局 handler 映射 402/409）
            throw e;
        } catch (Exception e) {
            events.add(AgentEvent.of(AgentEvent.ERROR, "code", "QUESTION_FAILED", "message", e.getMessage()));
            return events;
        }

        int lastSeq = turns.get(turns.size() - 1).getSeq();
        InterviewTurn next = newTurn(session, lastSeq + 1, 0, question, null);
        events.add(AgentEvent.of(AgentEvent.QUESTION, "turnSeq", next.getSeq(), "question", question,
                "role", nextRole.displayName(), "layer", 0));
        events.add(AgentEvent.of(AgentEvent.AWAIT_ANSWER, "turnSeq", next.getSeq()));
        return events;
    }

    // ---------- end ----------

    /**
     * 手动结束（中途打断）：Critic 出报告 → 落 critic_report → 会话置 DONE。幂等：已有报告直接重放。
     * 自然结束（答满最后一道主问题）走同一条 {@link #finish} 路径，只是 done 带 MAX_QUESTIONS_REACHED。
     */
    @Transactional
    public List<AgentEvent> end(Long sessionId) {
        InterviewSession session = sessionRepo.findByIdAndUserId(sessionId, currentUser.currentUserId())
                .orElseThrow(() -> new IllegalArgumentException("会话不存在: " + sessionId));
        return finish(session, null);
    }

    /**
     * 出报告 + 置 DONE 的唯一实现（手动结束与自然结束共用）。
     *
     * @param doneReason null=手动结束；{@code "MAX_QUESTIONS_REACHED"}=答满题量自然结束。
     *                   report 事件始终先于 done，前端据此只渲染一次报告。
     */
    private List<AgentEvent> finish(InterviewSession session, String doneReason) {
        Long reportId;
        Integer matchScore;
        String overallNotes;
        Object perTurnReview;
        Object collapsedPoints;
        Object expressionIssues;
        Object reminders;

        CriticReport existing = reportRepo.findBySessionIdAndUser(session.getId(), currentUser.currentUser()).orElse(null);
        if (existing != null) {
            // 幂等防御：answer 自动结束与手动 end 并发时，已有报告就重放，不再调一次 Critic
            reportId = existing.getId();
            matchScore = existing.getMatchScore();
            overallNotes = existing.getOverallNotes();
            perTurnReview = readJsonArray(existing.getPerTurnReview());
            collapsedPoints = readJsonArray(existing.getCollapsedPoints());
            expressionIssues = readJsonArray(existing.getExpressionIssues());
            reminders = readJsonArray(existing.getReminders());
        } else {
            List<InterviewTurn> turns = turnRepo.findBySessionIdOrderBySeqAsc(session.getId());
            List<TurnBrief> briefs = new ArrayList<>();
            for (InterviewTurn t : turns) {
                briefs.add(new TurnBrief(t.getSeq(), t.getQuestion(), t.getAnswer(), extractScore(t.getScoreJson())));
            }
            String jdText = session.getJdProfile() == null ? "" : nullToEmpty(session.getJdProfile().getRawText());
            Payload payload = critic.generate(session.getId(), jdText, briefs);

            CriticReport report = new CriticReport();
            report.setUser(currentUser.currentUser());
            report.setSession(session);
            report.setPerTurnReview(toJson(payload.perTurnReview()));
            report.setCollapsedPoints(toJson(payload.collapsedPoints()));
            report.setExpressionIssues(toJson(payload.expressionIssues()));
            report.setOverallNotes(payload.overallNotes());
            report.setReminders(toJson(payload.reminders()));
            report.setMatchScore(payload.matchScore());
            CriticReport saved = reportRepo.save(report);

            session.setStatus(SessionStatus.DONE);
            session.setMatchScore(payload.matchScore());
            sessionRepo.save(session);

            reportId = saved.getId();
            matchScore = payload.matchScore();
            overallNotes = payload.overallNotes();
            perTurnReview = payload.perTurnReview();
            collapsedPoints = payload.collapsedPoints();
            expressionIssues = payload.expressionIssues();
            reminders = payload.reminders();
        }

        AgentEvent reportEvent = AgentEvent.of(AgentEvent.REPORT, "reportId", reportId,
                "matchScore", matchScore,
                "perTurnReview", perTurnReview,
                "collapsedPoints", collapsedPoints,
                "expressionIssues", expressionIssues,
                "overallNotes", overallNotes,
                "reminders", reminders);
        AgentEvent doneEvent = doneReason == null
                ? AgentEvent.of(AgentEvent.DONE, "sessionId", session.getId())
                : AgentEvent.of(AgentEvent.DONE, "sessionId", session.getId(), "reason", doneReason);
        return List.of(reportEvent, doneEvent);
    }

    // ---------- 工具 ----------

    /** 轮数归一化：null/越界 → 默认 8；合法区间 [1, 8]。controller 层做 400 校验，这里做防御兜底。 */
    public static int normalizeMaxQuestions(Integer maxQuestions) {
        if (maxQuestions == null || maxQuestions < 1 || maxQuestions > MAX_MAIN_QUESTIONS) {
            return MAX_MAIN_QUESTIONS;
        }
        return maxQuestions;
    }

    private List<Role> resolveRoles(List<String> roleNames) {
        List<Role> roles = new ArrayList<>();
        if (roleNames != null) {
            for (String n : roleNames) {
                Role r = Role.fromDisplayName(n);
                if (r != null && r != Role.CRITIC && !roles.contains(r)) {
                    roles.add(r);
                }
            }
        }
        if (roles.isEmpty()) {
            roles.add(Role.TECH_DIGGER);
        }
        return roles;
    }

    /** 检索资料作为出题上下文；RAG 技术故障时静默降级为空上下文，402/409 必须透传打断面试。 */
    private String retrieveContext(String query, List<String> refs) {
        try {
            StringBuilder sb = new StringBuilder();
            for (var hit : rag.retrieve(query, RAG_TOP_K)) {
                refs.add(hit.chunk().id());
                sb.append("- ").append(hit.chunk().content()).append("\n");
            }
            return sb.toString();
        } catch (QuotaExhaustedException | IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            return "";
        }
    }

    private InterviewTurn newTurn(InterviewSession session, int seq, int layer, String question, String refs) {
        InterviewTurn t = new InterviewTurn();
        t.setUser(currentUser.currentUser());
        t.setSession(session);
        t.setSeq(seq);
        t.setLayer(layer);
        t.setQuestion(question);
        t.setRetrieveRefs(refs);
        return turnRepo.save(t);
    }

    private static Map<String, Object> scoreJsonOf(ScoreResult sr) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("score", sr.score());
        m.put("breakdown", sr.breakdown());
        m.put("missed_points", sr.missedPoints());
        m.put("followup", sr.followup());
        return m;
    }

    /** 错题本的知识点标签：优先用判分模型给的，没给就用题目前 30 字兜底（防整条链路静默失效）。 */
    private static String topicOf(ScoreResult sr, String question) {
        String kp = sr.knowledgePointOrEmpty();
        if (!kp.isEmpty()) {
            return kp;
        }
        String q = question == null ? "" : question.trim().replaceAll("\\s+", "");
        return q.length() <= 30 ? q : q.substring(0, 30);
    }

    /** 「现状」：把漏掉的关键点拼成一句话存进错题本，供复习时回看。 */
    private static String statusOf(ScoreResult sr) {
        if (sr.missedPoints() == null || sr.missedPoints().isEmpty()) {
            return null;
        }
        return String.join("；", sr.missedPoints());
    }

    /** 一句纠正（domain.md §4.1：实时只给一句，讲解归报告）。 */
    private static String oneLineCorrection(ScoreResult sr) {
        if (sr == null || sr.score() >= 8 || sr.missedPoints() == null || sr.missedPoints().isEmpty()) {
            return "";
        }
        String s = sr.missedPoints().get(0);
        return s == null ? "" : (s.length() > 80 ? s.substring(0, 80) + "…" : s);
    }

    private Integer extractScore(String scoreJson) {
        if (scoreJson == null || scoreJson.isBlank()) {
            return null;
        }
        try {
            JsonNode n = mapper.readTree(scoreJson);
            return n.hasNonNull("score") ? n.get("score").asInt() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private String toJson(Object obj) {
        try {
            return mapper.writeValueAsString(obj);
        } catch (Exception e) {
            return "[]";
        }
    }

    /** 重放既有报告时把 TEXT 列还原成结构化数组（解析失败给空数组，不阻断重放）。 */
    private Object readJsonArray(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return mapper.readValue(json, Object.class);
        } catch (Exception e) {
            return List.of();
        }
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
