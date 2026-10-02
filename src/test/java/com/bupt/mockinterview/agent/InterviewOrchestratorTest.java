package com.bupt.mockinterview.agent;

import com.bupt.mockinterview.billing.QuotaExhaustedException;
import com.bupt.mockinterview.domain.InterviewTurn;
import com.bupt.mockinterview.domain.JdProfile;
import com.bupt.mockinterview.domain.SessionStatus;
import com.bupt.mockinterview.domain.User;
import com.bupt.mockinterview.domain.repository.InterviewSessionRepository;
import com.bupt.mockinterview.domain.repository.InterviewTurnRepository;
import com.bupt.mockinterview.domain.repository.JdProfileRepository;
import com.bupt.mockinterview.llm.ScoreResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T6 验收：单角色（技术深挖官）跑完一轮问答 + 出 Critic 报告。
 *
 * 离线可跑（LLM 走 {@link StubLlmPort}），断言的是 api.md §4 的事件序列与 domain.md §4 的追问封顶规则。
 */
@SpringBootTest
class InterviewOrchestratorTest {

    @TestConfiguration
    static class StubConfig {
        @Bean
        @Primary
        StubLlmPort stubLlmPort() {
            return new StubLlmPort(INITIAL_QUESTIONS, INITIAL_SCORES, INITIAL_CRITIC_JSON);
        }
    }

    // 首题 + 后续两道主问题（第二道在答完追问后出）
    private static final List<String> INITIAL_QUESTIONS = List.of(
            "讲讲 synchronized 的锁升级过程",
            "MySQL 索引失效有哪些典型场景");
    // 第一次答崩（4 分 → 2 层追问起步）→ 追问后答到 7 分（1 层封顶 → 换下一题）
    private static final List<ScoreResult> INITIAL_SCORES = List.of(
            new ScoreResult(4, Map.of("正确性", 1, "完整性", 1, "深度", 1, "实证", 1),
                    List.of("没说偏向锁撤销会降级为轻量级锁"), "那偏向锁撤销之后呢？"),
            new ScoreResult(7, Map.of("正确性", 2, "完整性", 2, "深度", 2, "实证", 1),
                    List.of("没提批量重偏向"), ""));
    private static final String INITIAL_CRITIC_JSON = """
            {"matchScore":72,
             "perTurnReview":[{"turnSeq":1,"question":"讲讲 synchronized 的锁升级过程","score":4,
               "explanation":"锁升级是无锁→偏向→轻量→重量","correction":"漏了偏向锁撤销",
               "knowledgeSupplement":"批量重偏向与批量撤销","exemplarAnswer":"先无锁，首次进入加偏向锁…"}],
             "collapsedPoints":["synchronized 锁升级"],
             "expressionIssues":[{"issue":"口语化","guide":"先说结论再展开"}],
             "overallNotes":"整体要注意先给结论",
             "reminders":["补一下 Redis TTL 的设计原因"]}
            """;

    @Autowired
    private InterviewOrchestrator orchestrator;
    @Autowired
    private StubLlmPort stub;
    @Autowired
    private JdProfileRepository jdRepo;
    @Autowired
    private InterviewSessionRepository sessionRepo;
    @Autowired
    private InterviewTurnRepository turnRepo;
    @Autowired
    private User testUser;

    @BeforeEach
    void resetStub() {
        // stub 是 context 级单例：上一个测试方法注入的异常 / 用掉的游标必须复位
        stub.reset(INITIAL_QUESTIONS, INITIAL_SCORES, INITIAL_CRITIC_JSON);
    }

    private Long newJd() {
        JdProfile jd = new JdProfile();
        jd.setUser(testUser);
        jd.setRawText("招聘 Java 后端：要求精通并发编程、MySQL 索引与优化、Redis、Spring Boot。");
        jd.setJobType("Java 后端");
        return jdRepo.save(jd).getId();
    }

    @Test
    void singleRoleRoundTrip_emitsExpectedEventSequence() {
        // 1) start：出首题
        InterviewOrchestrator.StartResult started = orchestrator.start(newJd(), List.of("技术深挖官"));
        assertNotNull(started.sessionId());
        assertEquals(2, started.events().size());
        assertEquals(AgentEvent.QUESTION, started.events().get(0).type());
        assertEquals(AgentEvent.AWAIT_ANSWER, started.events().get(1).type());
        assertEquals("讲讲 synchronized 的锁升级过程", started.firstQuestion());
        assertEquals(0, started.events().get(0).get("layer"));
        assertEquals("技术深挖官", started.events().get(0).get("role"));

        // 2) 第一次作答：4 分 → 追问第 1 层
        List<AgentEvent> e1 = orchestrator.answer(started.sessionId(), "synchronized 是重量级锁，性能差。");
        assertEquals(List.of(AgentEvent.SCORE, AgentEvent.FOLLOWUP, AgentEvent.AWAIT_ANSWER),
                e1.stream().map(AgentEvent::type).toList());
        assertEquals(4, e1.get(0).get("score"));
        assertEquals(1, e1.get(1).get("layer"));

        // 3) 第二次作答（答追问）：7 分 → 已追问 1 层、目标 1 层 → 换下一道主问题
        List<AgentEvent> e2 = orchestrator.answer(started.sessionId(),
                "偏向锁撤销后会先升级为轻量级锁（CAS），竞争加剧才膨胀为重量级锁。");
        assertEquals(List.of(AgentEvent.SCORE, AgentEvent.QUESTION, AgentEvent.AWAIT_ANSWER),
                e2.stream().map(AgentEvent::type).toList());
        assertEquals(7, e2.get(0).get("score"));
        assertEquals(0, e2.get(1).get("layer"));
        assertEquals("MySQL 索引失效有哪些典型场景", e2.get(1).get("question"));

        // 4) 落库校验：3 个 turn（首题 / 追问 / 第二道主问题），层级 0,1,0
        List<InterviewTurn> turns = turnRepo.findBySessionIdOrderBySeqAsc(started.sessionId());
        assertEquals(3, turns.size());
        assertEquals(List.of(0, 1, 0), turns.stream().map(InterviewTurn::getLayer).toList());
        assertTrue(turns.get(0).getScoreJson().contains("\"score\":4"), "首轮 score_json 应落 4 分");
        assertNotNull(turns.get(0).getElapsedMs(), "应记录判分耗时");

        // 5) end：Critic 出报告 + done
        List<AgentEvent> e3 = orchestrator.end(started.sessionId());
        assertEquals(List.of(AgentEvent.REPORT, AgentEvent.DONE), e3.stream().map(AgentEvent::type).toList());
        assertEquals(72, e3.get(0).get("matchScore"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> perTurn = (List<Map<String, Object>>) e3.get(0).get("perTurnReview");
        assertEquals(1, perTurn.size());
        assertEquals("synchronized 锁升级", ((List<?>) e3.get(0).get("collapsedPoints")).get(0));

        assertEquals(SessionStatus.DONE, sessionRepo.findById(started.sessionId()).orElseThrow().getStatus());
        assertEquals(72, sessionRepo.findById(started.sessionId()).orElseThrow().getMatchScore());
    }

    @Test
    void perfectAnswer_doesNotFollowUp_andGoesToNextQuestion() {
        Long jdId = newJd();
        stub.questions.clear();
        stub.questions.addAll(List.of("题1", "题2"));
        stub.scores.clear();
        stub.scores.add(new ScoreResult(8, Map.of(), List.of(), ""));

        InterviewOrchestrator.StartResult started = orchestrator.start(jdId, List.of("技术深挖官"));
        List<AgentEvent> events = orchestrator.answer(started.sessionId(), "回答得非常好，满分。");

        // 8 分 → 不追问，直接下一题
        assertEquals(List.of(AgentEvent.SCORE, AgentEvent.QUESTION, AgentEvent.AWAIT_ANSWER),
                events.stream().map(AgentEvent::type).toList());
        assertEquals("", events.get(0).get("oneLineCorrection"));
    }

    /**
     * R1-3：判分时点数耗尽必须直接打断面试（→ 402），不能降级成 SCORE_FAILED 事件。
     */
    @Test
    void quotaExhaustedDuringScore_propagatesInsteadOfDegradeEvent() {
        InterviewOrchestrator.StartResult started = orchestrator.start(newJd(), List.of("技术深挖官"));
        stub.scoreError = new QuotaExhaustedException("服务点数已用完");

        QuotaExhaustedException ex = assertThrows(QuotaExhaustedException.class,
                () -> orchestrator.answer(started.sessionId(), "我正在回答，突然没点了。"));
        assertTrue(ex.getMessage().contains("点数"));
    }

    /**
     * R1-3：出下一道主问题时点数耗尽必须透传（→ 402），不能降级成 QUESTION_FAILED 事件。
     */
    @Test
    void quotaExhaustedDuringNextQuestion_propagatesInsteadOfDegradeEvent() {
        Long jdId = newJd();
        stub.questions.clear();
        stub.questions.addAll(List.of("首题"));   // start 用掉，之后 followUp 即抛
        stub.scores.clear();
        stub.scores.add(new ScoreResult(8, Map.of(), List.of(), ""));  // 满分 → 直接换下一题
        InterviewOrchestrator.StartResult started = orchestrator.start(jdId, List.of("技术深挖官"));
        stub.followUpError = new QuotaExhaustedException("服务点数已用完");

        assertThrows(QuotaExhaustedException.class,
                () -> orchestrator.answer(started.sessionId(), "满分回答，该出下一题了。"));
    }

    /**
     * R1-3：Critic 出报告时点数耗尽必须透传（→ 402），不能降级成「报告生成失败」占位报告。
     */
    @Test
    void quotaExhaustedDuringCritic_propagatesInsteadOfPlaceholderReport() {
        InterviewOrchestrator.StartResult started = orchestrator.start(newJd(), List.of("技术深挖官"));
        stub.chatError = new QuotaExhaustedException("服务点数已用完");

        assertThrows(QuotaExhaustedException.class, () -> orchestrator.end(started.sessionId()));
    }
}
