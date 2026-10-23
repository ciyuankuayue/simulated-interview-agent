package com.bupt.mockinterview.agent;

import com.bupt.mockinterview.agent.InterviewAgentRuntime.Action;
import com.bupt.mockinterview.agent.InterviewAgentRuntime.Decision;
import com.bupt.mockinterview.agent.InterviewEvidenceService.Evidence;
import com.bupt.mockinterview.agent.InterviewEvidenceService.Snapshot;
import com.bupt.mockinterview.billing.QuotaExhaustedException;
import com.bupt.mockinterview.billing.UsageScene;
import com.bupt.mockinterview.domain.InterviewSession;
import com.bupt.mockinterview.domain.InterviewTurn;
import com.bupt.mockinterview.domain.JdProfile;
import com.bupt.mockinterview.domain.SessionStatus;
import com.bupt.mockinterview.domain.User;
import com.bupt.mockinterview.llm.LlmException;
import com.bupt.mockinterview.llm.LlmPort;
import com.bupt.mockinterview.llm.LlmReply;
import com.bupt.mockinterview.llm.ScoreResult;
import com.bupt.mockinterview.llm.TokenUsage;
import com.bupt.mockinterview.llm.ToolCall;
import com.bupt.mockinterview.llm.chat.ModelRole;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 模型/证据桩驱动的离线行动回归：既验证模型自由决策，也验证执行器硬边界。 */
class InterviewAgentRuntimeTest {

    private static final TokenUsage USAGE = new TokenUsage(10, 10, 20, false);
    private final ObjectMapper mapper = new ObjectMapper();
    private final LlmPort llm = mock(LlmPort.class);
    private final InterviewEvidenceService evidence = mock(InterviewEvidenceService.class);
    private final InterviewAgentProperties limits = new InterviewAgentProperties();
    private final InterviewAgentRuntime runtime = new InterviewAgentRuntime(llm, mapper, evidence, limits);
    private final List<List<Map<String, Object>>> observed = new ArrayList<>();
    private InterviewSession session;

    @BeforeEach
    void initialize() {
        User user = new User("agent-offline", "unused");
        user.setId(7L);
        JdProfile jd = new JdProfile();
        jd.setId(3L);
        jd.setUser(user);
        jd.setRawText("后端工程师：缓存设计和跨团队协作");
        session = new InterviewSession();
        session.setId(21L);
        session.setUser(user);
        session.setJdProfile(jd);
        session.setResumeId(11L);
        session.setMaxQuestions(8);
        session.setStatus(SessionStatus.QUESTIONING);
        session.setRoles(Role.toJson(List.of(Role.TECH_DIGGER, Role.PROJECT_CHALLENGER, Role.BEHAVIOR_INTERVIEWER), mapper));
        when(evidence.snapshot(jd, 11L)).thenReturn(new Snapshot(jd.getRawText(), 11L, "本人负责缓存方案与沟通"));
        runtime.initialize(session);
    }

    @Test
    void firstQuestionChoosesModelRoleInsteadOfFirstEnabledRole() {
        given(decision("ASK_MAIN", Role.BEHAVIOR_INTERVIEWER, "请讲一次跨团队分歧中你采取的行动。"));

        Decision result = runtime.decide(session, List.of(), null);

        assertThat(result.action()).isEqualTo(Action.ASK_MAIN);
        assertThat(result.role()).isEqualTo(Role.BEHAVIOR_INTERVIEWER);
        assertThat(runtime.state(session).plan()).containsExactly("缓存边界：待考察", "协作行动：待核实");
        assertThat(observed.get(0).toString()).contains("后端工程师", "本人负责缓存方案", "enabledRoles");
    }

    @Test
    void toolResultIsFedBackBeforeFinalDecisionAndReferencedEvidenceIsRetained() throws Exception {
        Evidence hit = new Evidence("project_doc#4#0", "project_doc", "4", "项目资料声明：缓存由本人设计");
        when(evidence.search(any(Snapshot.class), eq("缓存个人贡献"), eq(5))).thenReturn(List.of(hit));
        given(tools(new ToolCall("search-1", "search_candidate_materials", "{\"query\":\"缓存个人贡献\"}")),
                decision("ASK_MAIN", Role.PROJECT_CHALLENGER, "这份缓存设计中你本人负责什么？", List.of(hit.id())));

        Decision result = runtime.decide(session, List.of(), null);

        assertThat(observed).hasSize(2);
        JsonNode returned = toolResult(observed.get(1), "search-1");
        assertThat(returned.path("status").asText()).isEqualTo("OK");
        assertThat(returned.path("data").get(0).path("content").asText()).contains("本人设计");
        assertThat(result.evidenceIds()).containsExactly(hit.id());
        assertThat(result.evidence()).contains(hit);
        assertThat(result.tools()).containsExactly(Map.of("name", "search_candidate_materials", "status", "OK"));
        assertThat(runtime.metadata(result)).contains(hit.id()).doesNotContain("reasoning_content");
    }

    @Test
    void lowScoreCanMoveToNewMainQuestionRatherThanForceProbe() {
        given(decision("ASK_MAIN", Role.PROJECT_CHALLENGER, "换个考点，你如何确认缓存改造效果？"));

        Decision result = runtime.decide(session, List.of(turn(Role.TECH_DIGGER, 0, "旧技术题")), score(1));

        assertThat(result.action()).isEqualTo(Action.ASK_MAIN);
        assertThat(result.role()).isEqualTo(Role.PROJECT_CHALLENGER);
        assertThat(observed.get(0).toString()).contains("latestAssessment", "\"score\":1");
    }

    @Test
    void perfectScoreCanStillProbeMissingMeasurementEvidence() {
        given(decision("PROBE", Role.PROJECT_CHALLENGER, "你提到的提升比例是如何测量的？"));

        Decision result = runtime.decide(session, List.of(turn(Role.PROJECT_CHALLENGER, 0, "请说明改造效果。")), score(8));

        assertThat(result.action()).isEqualTo(Action.PROBE);
        assertThat(result.role()).isEqualTo(Role.PROJECT_CHALLENGER);
    }

    @Test
    void unenabledRoleIsRejectedWithFeedbackUntilBudgetExhaustion() {
        session.setRoles(Role.toJson(List.of(Role.TECH_DIGGER), mapper));
        rejected(List.of(), decision("ASK_MAIN", Role.BEHAVIOR_INTERVIEWER, "你如何处理冲突？"), "未启用角色");
    }

    @Test
    void thirdFollowupLayerIsRejectedWithFeedbackUntilBudgetExhaustion() {
        rejected(List.of(turn(Role.PROJECT_CHALLENGER, 2, "第二层追问。")),
                decision("PROBE", Role.PROJECT_CHALLENGER, "继续第三层追问。"), "追问最多两层");
    }

    @Test
    void exhaustedMainQuestionLimitIsRejectedWithFeedback() {
        session.setMaxQuestions(1);
        rejected(List.of(turn(Role.TECH_DIGGER, 0, "唯一的主问题。")),
                decision("ASK_MAIN", Role.TECH_DIGGER, "不应出现的第二道主问题。"), "主问题预算已满");
    }

    @Test
    void normalizedDuplicateQuestionIsRejectedWithFeedback() {
        rejected(List.of(turn(Role.TECH_DIGGER, 0, "请解释缓存？")),
                decision("ASK_MAIN", Role.TECH_DIGGER, "请解释 缓存!"), "不能重复已问题目");
    }

    @Test
    void rejectedProposalCanBeCorrectedWithinTheBudget() {
        session.setRoles(Role.toJson(List.of(Role.TECH_DIGGER), mapper));
        given(decision("ASK_MAIN", Role.BEHAVIOR_INTERVIEWER, "错误角色的题目。"),
                decision("ASK_MAIN", Role.TECH_DIGGER, "缓存失效时如何保护数据库？"));

        Decision result = runtime.decide(session, List.of(), null);

        assertThat(result.role()).isEqualTo(Role.TECH_DIGGER);
        assertThat(observed.get(1).toString()).contains("该提案被执行器拒绝", "未启用角色");
    }

    @Test
    void unknownToolAndIdentityOverridesReturnStructuredRejectionsToModel() throws Exception {
        given(tools(new ToolCall("unknown", "delete_candidate_materials", "{}"),
                        new ToolCall("override-user", "search_candidate_materials", "{\"query\":\"缓存\",\"userId\":8}"),
                        new ToolCall("override-session", "get_interview_turns", "{\"sessionId\":22}")),
                decision("ASK_MAIN", Role.TECH_DIGGER, "缓存一致性的边界在哪里？"));

        Decision result = runtime.decide(session, List.of(), null);

        assertThat(toolResult(observed.get(1), "unknown").path("status").asText()).isEqualTo("NOT_ALLOWED");
        assertThat(toolResult(observed.get(1), "override-user").path("status").asText()).isEqualTo("INVALID_ARGUMENT");
        assertThat(toolResult(observed.get(1), "override-session").path("status").asText()).isEqualTo("INVALID_ARGUMENT");
        assertThat(result.tools()).hasSize(3);
        verify(evidence, never()).search(any(), anyString(), anyInt());
    }

    @Test
    void quotaAndConflictFromToolPropagateUnchanged() throws Exception {
        for (RuntimeException failure : List.of(new QuotaExhaustedException("额度耗尽"), new IllegalStateException("并发冲突"))) {
            doThrow(failure).when(evidence).search(any(), anyString(), anyInt());
            given(tools(new ToolCall("search", "search_candidate_materials", "{\"query\":\"缓存\"}")));

            assertThatThrownBy(() -> runtime.decide(session, List.of(), null)).isSameAs(failure);
            assertThat(observed).hasSize(1);
        }
    }

    @Test
    void missingEvidenceCannotBeInventedAsAValidReference() throws Exception {
        when(evidence.search(any(), anyString(), anyInt())).thenReturn(List.of());
        given(tools(new ToolCall("empty", "search_candidate_materials", "{\"query\":\"压测数据\"}")),
                decision("ASK_MAIN", Role.PROJECT_CHALLENGER, "该压测数字是什么？", List.of("project_doc#missing#0")),
                decision("ASK_MAIN", Role.PROJECT_CHALLENGER, "你是否有这次改造的测量依据？"));

        Decision result = runtime.decide(session, List.of(), null);

        assertThat(toolResult(observed.get(1), "empty").path("status").asText()).isEqualTo("NOT_FOUND");
        assertThat(observed.get(2).toString()).contains("引用了未获取的证据ID");
        assertThat(result.evidenceIds()).isEmpty();
        assertThat(result.evidence()).extracting(Evidence::id).doesNotContain("project_doc#missing#0");
    }

    @Test
    void ordinaryRetrievalFailureReturnsErrorRatherThanNoEvidence() throws Exception {
        when(evidence.search(any(), anyString(), anyInt())).thenThrow(new IOException("索引不可用"));
        given(tools(new ToolCall("failed", "search_candidate_materials", "{\"query\":\"压测\"}")),
                decision("ASK_MAIN", Role.PROJECT_CHALLENGER, "你能先介绍测量方式吗？"));

        runtime.decide(session, List.of(), null);

        JsonNode result = toolResult(observed.get(1), "failed");
        assertThat(result.path("status").asText()).isEqualTo("ERROR");
        assertThat(result.path("message").asText()).contains("不能视为没有资料");
    }

    @Test
    void reconstructedRuntimeRestoresRolePlanAndPreviouslyObservedEvidence() throws Exception {
        Evidence hit = new Evidence("note#4#0", "note", "4", "本人在缓存改造中的测量笔记");
        when(evidence.search(any(), anyString(), anyInt())).thenReturn(List.of(hit));
        given(tools(new ToolCall("find", "search_candidate_materials", "{\"query\":\"缓存\"}")),
                decision("ASK_MAIN", Role.PROJECT_CHALLENGER, "请解释本人贡献。", List.of(hit.id())));
        Decision first = runtime.decide(session, List.of(), null);
        InterviewTurn persistedTurn = turn(Role.PROJECT_CHALLENGER, 0, first.question());
        persistedTurn.setAgentMetadata(runtime.metadata(first));

        InterviewAgentRuntime restored = new InterviewAgentRuntime(llm, mapper, evidence, limits);
        given(decision("PROBE", Role.PROJECT_CHALLENGER, "这份测量笔记中你怎样控制变量？", List.of(hit.id())));
        Decision next = restored.decide(session, List.of(persistedTurn), score(8));

        assertThat(restored.roleOf(persistedTurn)).isEqualTo(Role.PROJECT_CHALLENGER);
        assertThat(restored.state(session).plan()).containsExactly("缓存边界：待考察", "协作行动：待核实");
        assertThat(next.role()).isEqualTo(Role.PROJECT_CHALLENGER);
        assertThat(next.action()).isEqualTo(Action.PROBE);
        assertThat(next.evidenceIds()).containsExactly(hit.id());
        assertThat(observed.get(0).toString()).contains("本人贡献", hit.content());
    }

    @Test
    void exceedingToolBudgetFailsBeforeExecutingAnyTool() throws Exception {
        limits.setMaxToolCalls(0);
        given(tools(new ToolCall("find", "search_candidate_materials", "{\"query\":\"缓存\"}")));

        assertThatThrownBy(() -> runtime.decide(session, List.of(), null))
                .isInstanceOf(LlmException.class).hasMessageContaining("工具调用预算已用尽");
        verify(evidence, never()).search(any(), anyString(), anyInt());
    }

    @Test
    void longLegalInterviewIsCompactedBeforeModelCallAndCanStillEnd() throws Exception {
        String longJd = "岗".repeat(6000);
        when(evidence.snapshot(session.getJdProfile(), 11L)).thenReturn(new Snapshot(longJd, 11L, "本人负责缓存方案"));
        runtime.initialize(session);
        List<String> missed = IntStream.range(0, 10).mapToObj(i -> "漏".repeat(500)).toList();
        List<Evidence> savedEvidence = IntStream.range(0, 12)
                .mapToObj(i -> new Evidence("note#" + i + "#0", "note", "" + i, "据".repeat(900))).toList();
        List<InterviewTurn> history = new ArrayList<>();
        for (int i = 0; i < 24; i++) {
            InterviewTurn turn = turn(Role.PROJECT_CHALLENGER, i % 3, "第" + (i + 1) + "轮考察问题：" + "问".repeat(350));
            turn.setId(100L + i);
            turn.setSeq(i + 1);
            String marker = i == 23 ? "最后一轮最新回答" : "第" + (i + 1) + "轮回答";
            turn.setAnswer(marker + "答".repeat(1600 - marker.length()));
            turn.setScoreJson(mapper.writeValueAsString(Map.of("score", 6,
                    "breakdown", Map.of("正确性", 2, "完整性", 2, "深度", 1, "实证", 1),
                    "missed_points", missed, "followup", "请核实测量依据", "knowledge_point", "缓存测量")));
            turn.setAgentMetadata(mapper.writeValueAsString(Map.of("role", Role.PROJECT_CHALLENGER.name(),
                    "goal", "核实测量", "evidence", i == 23 ? savedEvidence : List.of(),
                    "evidenceIds", i == 23 ? List.of("note#11#0") : List.of())));
            history.add(turn);
        }
        given(decision("END", Role.PROJECT_CHALLENGER, "", List.of("note#11#0")));

        Decision result = runtime.decide(session, history, new ScoreResult(6, Map.of(), missed, "", "缓存测量"));

        assertThat(result.action()).isEqualTo(Action.END);
        assertThat(result.question()).isEmpty();
        assertThat(observed).hasSize(1);
        String observation = observed.get(0).get(1).get("content").toString();
        assertThat(observation.substring(observation.indexOf('\n') + 1).length()).isLessThanOrEqualTo(26000);
        assertThat(mapper.writeValueAsString(observed.get(0)).length()).isLessThanOrEqualTo(48000);
        assertThat(observation).contains("最后一轮最新回答", "note#11#0");
        assertThat(result.evidence()).containsAll(savedEvidence);
        assertThat(result.modelCalls()).isEqualTo(1);
        assertThat(result.tokens()).isEqualTo(20);
    }

    @Test
    void newlyReferencedEvidenceSurvivesFullHistoryWindowAndIsAvailableToNextDecision() throws Exception {
        List<Evidence> oldEvidence = IntStream.range(0, 12)
                .mapToObj(i -> new Evidence("note#" + i + "#0", "note", "" + i, "旧资料" + i)).toList();
        InterviewTurn main = turn(Role.PROJECT_CHALLENGER, 0, "请说明缓存改造的测量过程。");
        main.setAgentMetadata(mapper.writeValueAsString(Map.of("role", Role.PROJECT_CHALLENGER.name(),
                "goal", "核实测量", "evidence", oldEvidence, "evidenceIds", List.of())));
        Evidence newest = new Evidence("note#13#0", "note", "13", "新查到的证据声明：压测控制了相同流量与数据量");
        when(evidence.search(any(), anyString(), eq(5))).thenReturn(List.of(newest));
        given(tools(new ToolCall("find-newest", "search_candidate_materials", "{\"query\":\"压测控制变量\"}")),
                decision("PROBE", Role.PROJECT_CHALLENGER, "你如何保持相同流量？", List.of(newest.id())));

        Decision first = runtime.decide(session, List.of(main), score(6));
        assertThat(first.evidence()).containsAll(oldEvidence).contains(newest);
        assertThat(first.evidenceIds()).containsExactly(newest.id());
        InterviewTurn probe = turn(Role.PROJECT_CHALLENGER, 1, first.question());
        probe.setId(32L);
        probe.setSeq(2);
        probe.setAgentMetadata(runtime.metadata(first));

        InterviewAgentRuntime restored = new InterviewAgentRuntime(llm, mapper, evidence, limits);
        given(decision("CLARIFY", Role.PROJECT_CHALLENGER, "数据量相同是如何确认的？", List.of(newest.id())));
        Decision next = restored.decide(session, List.of(main, probe), score(7));

        assertThat(next.action()).isEqualTo(Action.CLARIFY);
        assertThat(next.evidenceIds()).containsExactly(newest.id());
        assertThat(next.evidence()).contains(newest);
        String observation = observed.get(0).get(1).get("content").toString();
        JsonNode data = mapper.readTree(observation.substring(observation.indexOf('\n') + 1));
        boolean includesNewEvidence = false;
        for (JsonNode entry : data.path("evidence")) {
            if (newest.id().equals(entry.path("id").asText())) {
                includesNewEvidence = true;
                assertThat(entry.path("content").asText()).isEqualTo(newest.content());
            }
        }
        assertThat(includesNewEvidence).isTrue();
    }

    @Test
    void endAfterEvidenceLookupPersistsExecutionStatisticsAndRestoresLastDecision() throws Exception {
        Evidence hit = new Evidence("real_interview#4#0", "real_interview", "4", "真实面试记录中的测量声明");
        when(evidence.search(any(), anyString(), eq(5))).thenReturn(List.of(hit));
        given(tools(new ToolCall("final-check", "search_candidate_materials", "{\"query\":\"测量依据\"}")),
                decision("END", Role.PROJECT_CHALLENGER, "", List.of(hit.id())));
        long wallStart = System.nanoTime();

        Decision result = runtime.decide(session, List.of(turn(Role.PROJECT_CHALLENGER, 2, "最后一次追问。")), score(8));

        long wallElapsed = (System.nanoTime() - wallStart) / 1_000_000;
        assertThat(result.action()).isEqualTo(Action.END);
        assertThat(result.modelCalls()).isEqualTo(2);
        assertThat(result.toolCalls()).isEqualTo(1);
        assertThat(result.tokens()).isEqualTo(40);
        assertThat(result.elapsedMs()).isBetween(0L, wallElapsed);
        assertThat(result.evidenceIds()).containsExactly(hit.id());
        assertThat(result.evidence()).contains(hit);

        InterviewAgentRuntime restored = new InterviewAgentRuntime(llm, mapper, evidence, limits);
        Decision persisted = restored.state(session).lastDecision();
        assertThat(persisted).isEqualTo(result);
        assertThat(persisted.tools()).containsExactly(Map.of("name", "search_candidate_materials", "status", "OK"));
        assertThat(restored.assessmentContext(session, List.of())).contains(hit.id(), hit.content(), "lastAction");
        JsonNode metadata = mapper.readTree(restored.metadata(persisted));
        assertThat(metadata.path("modelCalls").asInt()).isEqualTo(2);
        assertThat(metadata.path("toolCalls").asInt()).isEqualTo(1);
        assertThat(metadata.path("tokens").asLong()).isEqualTo(40);
        assertThat(metadata.path("elapsedMs").asLong()).isEqualTo(result.elapsedMs());
    }

    private void rejected(List<InterviewTurn> turns, LlmReply invalid, String feedback) {
        limits.setMaxModelCalls(2);
        String initialState = session.getAgentState();
        given(invalid);

        assertThatThrownBy(() -> runtime.decide(session, turns, score(4)))
                .isInstanceOf(LlmException.class).hasMessageContaining("预算内产生有效行动");
        assertThat(observed).hasSize(2);
        assertThat(observed.get(1).toString()).contains("该提案被执行器拒绝", feedback);
        assertThat(session.getAgentState()).isEqualTo(initialState);
    }

    private void given(LlmReply... replies) {
        observed.clear();
        AtomicInteger index = new AtomicInteger();
        doAnswer(invocation -> {
                    List<Map<String, Object>> messages = invocation.getArgument(2);
                    // Runtime mutates its message list between calls; retain each call's actual observations.
                    observed.add(messages.stream().map(m -> (Map<String, Object>) new LinkedHashMap<>(m)).toList());
                    return replies[Math.min(index.getAndIncrement(), replies.length - 1)];
                }).when(llm).chat(eq(ModelRole.FAST), eq(false), anyList(), anyList(), eq(false), anyInt(), eq(UsageScene.FOLLOWUP));
    }

    private LlmReply decision(String action, Role role, String question) {
        return decision(action, role, question, List.of());
    }

    private LlmReply decision(String action, Role role, String question, List<String> refs) {
        try {
            String json = mapper.writeValueAsString(Map.of("action", action, "role", role.name(), "question", question,
                    "goal", "核实候选人的实际能力", "reason", "根据当前回答选择下一步",
                    "plan", List.of("缓存边界：待考察", "协作行动：待核实"), "evidenceIds", refs));
            return new LlmReply(json, null, USAGE, List.of());
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static LlmReply tools(ToolCall... calls) {
        return new LlmReply("", null, USAGE, List.of(calls));
    }

    private JsonNode toolResult(List<Map<String, Object>> messages, String callId) throws Exception {
        Map<String, Object> result = messages.stream()
                .filter(m -> "tool".equals(m.get("role")) && callId.equals(m.get("tool_call_id"))).findFirst().orElseThrow();
        return mapper.readTree(result.get("content").toString());
    }

    private InterviewTurn turn(Role role, int layer, String question) {
        InterviewTurn turn = new InterviewTurn();
        turn.setId(31L);
        turn.setUser(session.getUser());
        turn.setSession(session);
        turn.setSeq(1);
        turn.setLayer(layer);
        turn.setQuestion(question);
        turn.setAnswer("我负责缓存设计，但测量依据还需要补充。");
        turn.setAgentMetadata("{\"role\":\"" + role.name() + "\",\"goal\":\"核实边界\",\"evidence\":[]}");
        return turn;
    }

    private static ScoreResult score(int total) {
        return new ScoreResult(total, Map.of(), List.of("测量依据"), "", "缓存边界");
    }
}
