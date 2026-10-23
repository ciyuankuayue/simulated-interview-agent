package com.bupt.mockinterview.agent;

import com.bupt.mockinterview.billing.QuotaExhaustedException;
import com.bupt.mockinterview.billing.UsageScene;
import com.bupt.mockinterview.domain.*;
import com.bupt.mockinterview.domain.repository.*;
import com.bupt.mockinterview.llm.*;
import com.bupt.mockinterview.llm.chat.ModelRole;
import com.bupt.mockinterview.rag.RagService;
import com.bupt.mockinterview.memory.WeakPointMemoryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.transaction.annotation.Transactional;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real H2/repositories/orchestrator/runtime with deterministic provider replies, no paid calls. */
@SpringBootTest(properties = "app.interview.agent-enabled=true")
@Transactional
class AgentInterviewFlowTest {
    @Autowired InterviewOrchestrator orchestrator;
    @Autowired InterviewSessionRepository sessions;
    @Autowired InterviewTurnRepository turns;
    @Autowired JdProfileRepository jds;
    @Autowired ResumeVersionRepository resumes;
    @Autowired User testUser;
    @Autowired ObjectMapper mapper;
    @Autowired EntityManager em;
    @MockBean LlmPort llm;
    @MockBean RagService rag;
    @MockBean WeakPointMemoryService memory;
    private final ArrayDeque<LlmReply> decisions = new ArrayDeque<>();
    private static final TokenUsage USAGE = new TokenUsage(20, 10, 30, false);

    @BeforeEach void prepare() throws Exception {
        decisions.clear();
        when(rag.retrieve(anyString(), anyInt())).thenReturn(List.of());
        when(llm.chat(eq(ModelRole.FAST), eq(false), anyList(), anyList(), eq(false), anyInt(), eq(UsageScene.FOLLOWUP)))
                .thenAnswer(call -> {
                    if (decisions.isEmpty()) throw new LlmException("test decision provider unavailable");
                    return decisions.removeFirst();
                });
        when(llm.chat(eq(ModelRole.REASONER), eq(true), anyList(), isNull(), eq(true), anyInt(), eq(UsageScene.CRITIC)))
                .thenReturn(new LlmReply("""
                    {"matchScore":70,"perTurnReview":[],"collapsedPoints":[],"expressionIssues":[],
                     "overallNotes":"仅覆盖本场已考察能力","reminders":[],"behaviorReview":null}
                    """, null, USAGE));
        when(llm.score(any(), anyString(), anyString(), anyString())).thenReturn(score(4));
    }

    private Long jd() {
        JdProfile jd = new JdProfile(); jd.setUser(testUser);
        jd.setRawText("招聘Python工程师，考查asyncio并发和本人项目贡献。"); jd.setJobType("Python工程师");
        return jds.saveAndFlush(jd).getId();
    }
    private void action(String action, String role, String question) throws Exception {
        decisions.add(new LlmReply(mapper.writeValueAsString(Map.of("action", action, "role", role,
                "question", question, "goal", "核实本人贡献", "reason", "根据现场回答调整考察",
                "plan", List.of("异步机制待核实", "个人贡献待考察"), "evidenceIds", List.of())), null, USAGE));
    }
    private ScoreResult score(int total) {
        return new ScoreResult(total, Map.of("正确性", total / 4, "完整性", total / 4, "深度", total / 4, "实证", total / 4), List.of(), "", "异步并发");
    }

    @Test void lowScoreCanMoveToAnotherRoleInsteadOfForcedFollowup() throws Exception {
        action("ASK_MAIN", "TECH_DIGGER", "asyncio如何调度协程？");
        action("ASK_MAIN", "PROJECT_CHALLENGER", "你本人负责异步任务的哪一部分？");
        when(llm.score(any(), anyString(), anyString(), anyString())).thenReturn(score(0));
        var start = orchestrator.start(jd(), List.of("技术深挖官", "项目质疑官"), 3, null);
        var events = orchestrator.answer(start.sessionId(), "这个机制我还不会");
        assertTrue(events.stream().anyMatch(e -> e.type().equals("question") && e.get("role").equals("项目质疑官")));
        assertFalse(events.stream().anyMatch(e -> e.type().equals("followup")));
        var stored = turns.findBySessionIdOrderBySeqAsc(start.sessionId());
        assertEquals(0, stored.get(1).getLayer());
        assertEquals("PROJECT_CHALLENGER", mapper.readTree(stored.get(1).getAgentMetadata()).path("role").asText());
    }

    @Test void perfectScoreCanStillProbeAndPersistedRoleControlsScoringAfterReload() throws Exception {
        action("ASK_MAIN", "BEHAVIOR_INTERVIEWER", "讲一次你协调团队分歧的经历？");
        action("PROBE", "BEHAVIOR_INTERVIEWER", "其中你本人具体采取了什么行动？");
        when(llm.score(any(), anyString(), anyString(), anyString())).thenReturn(score(8));
        var start = orchestrator.start(jd(), List.of("技术深挖官", "行为面试官"), 3, null);
        em.flush(); em.clear();
        var events = orchestrator.answer(start.sessionId(), "我先整理分歧，然后组织双方确认共同目标");
        verify(llm).score(eq(ScoreTrack.BEHAVIOR), anyString(), anyString(), anyString());
        assertTrue(events.stream().anyMatch(e -> e.type().equals("followup") && e.get("layer").equals(1)));
        var session = sessions.findByIdAndUserId(start.sessionId(), testUser.getId()).orElseThrow();
        assertEquals(1, mapper.readTree(session.getAgentState()).path("version").asInt());
        assertEquals("PROBE", mapper.readTree(session.getAgentState()).path("lastDecision").path("action").asText());
    }

    @Test void agentCanEndEarlyAndReportReplayDoesNotCallModelAgain() throws Exception {
        action("ASK_MAIN", "TECH_DIGGER", "协程在何时让出执行权？");
        action("END", "TECH_DIGGER", "");
        var start = orchestrator.start(jd(), List.of("技术深挖官"), 3, null);
        var events = orchestrator.answer(start.sessionId(), "在等待可挂起的异步操作时");
        assertTrue(events.stream().anyMatch(e -> e.type().equals("report")));
        assertTrue(events.stream().anyMatch(e -> e.type().equals("done")));
        assertEquals(SessionStatus.DONE, sessions.findByIdAndUserId(start.sessionId(), testUser.getId()).orElseThrow().getStatus());
        orchestrator.end(start.sessionId());
        verify(llm, times(1)).chat(eq(ModelRole.REASONER), eq(true), anyList(), isNull(), eq(true), anyInt(), eq(UsageScene.CRITIC));
    }

    @Test void selectedResumeAndJdStayAtTheirStartSnapshot() throws Exception {
        Long jdId = jd();
        ResumeVersion resume = new ResumeVersion(); resume.setUser(testUser); resume.setContent("我负责Python报表任务");
        resume = resumes.saveAndFlush(resume);
        action("ASK_MAIN", "PROJECT_CHALLENGER", "你负责报表任务中的哪一部分？");
        action("ASK_MAIN", "PROJECT_CHALLENGER", "报表任务如何处理重试？");
        var start = orchestrator.start(jdId, List.of("项目质疑官"), 3, resume.getId());
        JdProfile changed = jds.findByIdAndUserId(jdId, testUser.getId()).orElseThrow();
        changed.setRawText("NEW_JD_MUST_NOT_REPLACE_SNAPSHOT");
        resume.setContent("NEW_RESUME_MUST_NOT_REPLACE_SNAPSHOT"); em.flush();
        orchestrator.answer(start.sessionId(), "我写了调度和错误记录");
        verify(llm).score(any(), anyString(), anyString(), argThat(context -> context.contains("Python报表任务")
                && !context.contains("NEW_RESUME") && !context.contains("NEW_JD")));
    }

    @Test void failedNextDecisionCanResumeWithoutRescoringOrRewritingAnswer() throws Exception {
        action("ASK_MAIN", "TECH_DIGGER", "协程如何取消？");
        var start = orchestrator.start(jd(), List.of("技术深挖官"), 3, null);
        var failed = orchestrator.answer(start.sessionId(), "通过取消任务并传播异常");
        assertTrue(failed.stream().anyMatch(e -> e.type().equals("error") && e.get("code").equals("QUESTION_FAILED")));
        assertEquals("通过取消任务并传播异常", turns.findBySessionIdOrderBySeqAsc(start.sessionId()).get(0).getAnswer());
        action("ASK_MAIN", "TECH_DIGGER", "取消过程中如何释放资源？");
        var recovered = orchestrator.answer(start.sessionId(), "通过取消任务并传播异常");
        assertTrue(recovered.stream().anyMatch(e -> e.type().equals("question")));
        verify(llm, times(1)).score(any(), anyString(), anyString(), anyString());
    }

    @Test void failedScoreDoesNotEraseRoleOrEnterWeakPointMemory() throws Exception {
        action("ASK_MAIN", "BEHAVIOR_INTERVIEWER", "一次沟通失败让你学到了什么？");
        action("END", "BEHAVIOR_INTERVIEWER", "");
        when(llm.score(any(), anyString(), anyString(), anyString())).thenThrow(new LlmException("invalid score"));
        var start = orchestrator.start(jd(), List.of("行为面试官"), 3, null);
        orchestrator.answer(start.sessionId(), "我意识到要先听清对方的诉求");
        var turn = turns.findBySessionIdOrderBySeqAsc(start.sessionId()).get(0);
        assertEquals("behavior", mapper.readTree(turn.getScoreJson()).path("track").asText());
        assertFalse(mapper.readTree(turn.getScoreJson()).has("score"));
        assertEquals("BEHAVIOR_INTERVIEWER", mapper.readTree(turn.getAgentMetadata()).path("role").asText());
        verifyNoInteractions(memory);
    }

    @Test void legacySessionKeepsItsOriginalPathWhenNewSessionFlagIsEnabled() throws Exception {
        InterviewSession legacy = new InterviewSession(); legacy.setUser(testUser);
        legacy.setJdProfile(jds.findByIdAndUserId(jd(), testUser.getId()).orElseThrow());
        legacy.setRoles("[\"技术深挖官\"]"); legacy.setStatus(SessionStatus.QUESTIONING); legacy.setMaxQuestions(3);
        sessions.saveAndFlush(legacy);
        InterviewTurn turn = new InterviewTurn(); turn.setUser(testUser); turn.setSession(legacy);
        turn.setQuestion("旧版本题目"); turn.setLayer(0); turn.setSeq(1); turns.saveAndFlush(turn);
        when(llm.score(eq(ScoreTrack.TECH), anyString(), anyString())).thenReturn(score(8));
        when(llm.followUp(anyString(), anyString())).thenReturn(new LlmReply("旧流程的下一题", null));
        var events = orchestrator.answer(legacy.getId(), "旧流程回答");
        assertTrue(events.stream().anyMatch(e -> "旧流程的下一题".equals(e.get("question"))));
        assertNull(legacy.getAgentState());
        verify(llm, never()).chat(eq(ModelRole.FAST), anyBoolean(), anyList(), anyList(), anyBoolean(), anyInt(), any());
    }

    @Test
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void quotaErrorFromDecisionRemains402AndPaidScoreIsCommittedForRecovery() throws Exception {
        action("ASK_MAIN", "TECH_DIGGER", "异步任务为什么需要超时？");
        var start = orchestrator.start(jd(), List.of("技术深挖官"), 3, null);
        when(llm.chat(eq(ModelRole.FAST), eq(false), anyList(), anyList(), eq(false), anyInt(), eq(UsageScene.FOLLOWUP)))
                .thenThrow(QuotaExhaustedException.forMode(KeyMode.PLATFORM));
        assertThrows(QuotaExhaustedException.class, () -> orchestrator.answer(start.sessionId(), "避免一直等待"));
        var persisted = turns.findBySessionIdOrderBySeqAsc(start.sessionId()).get(0);
        assertEquals("避免一直等待", persisted.getAnswer());
        assertEquals(4, mapper.readTree(persisted.getScoreJson()).path("score").asInt());
        reset(llm);
        when(llm.chat(eq(ModelRole.FAST), eq(false), anyList(), anyList(), eq(false), anyInt(), eq(UsageScene.FOLLOWUP)))
                .thenReturn(new LlmReply(mapper.writeValueAsString(Map.of("action", "ASK_MAIN", "role", "TECH_DIGGER",
                        "question", "超时之后如何清理资源？", "goal", "资源管理", "reason", "核实边界",
                        "plan", List.of("资源管理待考察"), "evidenceIds", List.of())), null, USAGE));
        orchestrator.answer(start.sessionId(), "避免一直等待");
        verify(llm, never()).score(any(), anyString(), anyString(), anyString());
    }

    @Test
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void databaseSessionLockRejectsConcurrentAnswerBeforeAnotherScoreCall() throws Exception {
        action("ASK_MAIN", "TECH_DIGGER", "异步任务的取消语义是什么？");
        action("ASK_MAIN", "TECH_DIGGER", "如何处理取消后的资源释放？");
        var start = orchestrator.start(jd(), List.of("技术深挖官"), 3, null);
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        when(llm.score(any(), anyString(), anyString(), anyString())).thenAnswer(call -> {
            entered.countDown();
            assertTrue(release.await(10, java.util.concurrent.TimeUnit.SECONDS));
            return score(8);
        });
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            var first = executor.submit(() -> {
                com.bupt.mockinterview.security.UserContext.set(testUser.getId(), testUser.getUsername());
                com.bupt.mockinterview.security.TenantContext.set(testUser.getId());
                try { return orchestrator.answer(start.sessionId(), "我会传播取消并清理资源"); }
                finally {
                    com.bupt.mockinterview.security.UserContext.clear();
                    com.bupt.mockinterview.security.TenantContext.clear();
                }
            });
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
            // H2 marks a timed-out connection broken; Hikari can wrap its rollback as JpaSystemException.
            // Either translation must reject the competing transaction before a second score call.
            assertThrows(org.springframework.dao.DataAccessException.class,
                    () -> orchestrator.answer(start.sessionId(), "并发重复提交"));
            release.countDown(); first.get(10, java.util.concurrent.TimeUnit.SECONDS);
            verify(llm, times(1)).score(any(), anyString(), anyString(), anyString());
            assertEquals(2, turns.findBySessionIdOrderBySeqAsc(start.sessionId()).size());
        } finally { release.countDown(); executor.shutdownNow(); }
    }
}
