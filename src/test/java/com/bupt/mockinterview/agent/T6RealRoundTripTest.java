package com.bupt.mockinterview.agent;

import com.bupt.mockinterview.domain.JdProfile;
import com.bupt.mockinterview.domain.WeakPoint;
import com.bupt.mockinterview.domain.repository.JdProfileRepository;
import com.bupt.mockinterview.llm.LlmProperties;
import com.bupt.mockinterview.memory.WeakPointMemoryService;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T6 真实链路验收：走真 DeepSeek（出题 / 判分 / Critic），不走 stub。
 *
 * 未 set {@code DEEPSEEK_API_KEY} 时整类跳过，不污染 CI。
 * 跑法（repo 根目录，用 JDK 21）：
 * <pre>
 *   JAVA_HOME="C:\\Users\\19284\\jdk-21" DEEPSEEK_API_KEY=sk-xxx ./mvnw.cmd test -Dtest=T6RealRoundTripTest
 * </pre>
 */
@SpringBootTest
/**
 * ⚠️ @Transactional：真实链路会真写库（session/turn/report/weak_point），
 * 回滚掉，避免污染其他测试类共享的同一个 H2 mem 库（尤其错题本相关的空/非空断言）。
 */
@Transactional
class T6RealRoundTripTest {

    @Autowired
    private InterviewOrchestrator orchestrator;
    @Autowired
    private JdProfileRepository jdRepo;
    @Autowired
    private LlmProperties props;
    @Autowired
    private WeakPointMemoryService memory;

    @Test
    void realRoundTrip() {
        Assumptions.assumeTrue(props.getApiKey() != null && !props.getApiKey().isBlank(),
                "DEEPSEEK_API_KEY 未设置，跳过真实链路验收");

        JdProfile jd = new JdProfile();
        jd.setRawText("招聘 Java 后端（3-5 年）：要求熟悉并发编程、JVM、MySQL 索引与 SQL 优化、Redis、Spring Boot；有高并发项目经验优先。");
        jd.setJobType("Java 后端");
        Long jdId = jdRepo.save(jd).getId();

        System.out.println("[real] model-flash=" + props.getModelFlash() + " model-reasoner=" + props.getModelReasoner());

        InterviewOrchestrator.StartResult started = orchestrator.start(jdId, List.of("技术深挖官"));
        System.out.println("[real] sessionId=" + started.sessionId());
        System.out.println("[real] Q1: " + started.firstQuestion());
        assertNotNull(started.firstQuestion());
        assertFalse(started.firstQuestion().isBlank());

        List<AgentEvent> a1 = orchestrator.answer(started.sessionId(),
                "synchronized 是 Java 关键字，可以保证原子性和可见性，底层是 monitor 对象，性能比较差。");
        a1.forEach(e -> System.out.println("[real] event " + e.type() + " -> " + e.data()));
        assertTrue(a1.stream().anyMatch(e -> AgentEvent.SCORE.equals(e.type())), "应产出 score 事件");
        Object score = a1.stream().filter(e -> AgentEvent.SCORE.equals(e.type())).findFirst().orElseThrow().get("score");
        assertNotNull(score, "真实判分应有 score");

        List<AgentEvent> all = new ArrayList<>(a1);

        // 若触发了追问，再答一轮（真实链路下不强制，有则答、无则跳过）
        if (a1.stream().anyMatch(e -> AgentEvent.FOLLOWUP.equals(e.type()))) {
            List<AgentEvent> a2 = orchestrator.answer(started.sessionId(),
                    "JDK 1.6 之后有锁升级：无锁 → 偏向锁 → 轻量级锁（CAS 自旋）→ 竞争激烈时膨胀为重量级锁。");
            a2.forEach(e -> System.out.println("[real] event " + e.type() + " -> " + e.data()));
            all.addAll(a2);
        }

        List<AgentEvent> ended = orchestrator.end(started.sessionId());
        ended.forEach(e -> System.out.println("[real] event " + e.type() + " -> " + e.data()));
        AgentEvent report = ended.stream().filter(e -> AgentEvent.REPORT.equals(e.type())).findFirst().orElseThrow();
        assertNotNull(report.get("reportId"), "应落库 critic_report");
        System.out.println("[real] matchScore=" + report.get("matchScore"));
        System.out.println("[real] overallNotes=" + report.get("overallNotes"));

        // T8：真实判分 → 错题本。只要本场出现过 ≤3 分的轮次，错题本就该有记录
        List<WeakPoint> queue = memory.queue();
        System.out.println("[real] 错题本 = " + queue.stream()
                .map(w -> w.getKnowledgePoint() + "(x" + w.getWrongCount() + ", stage" + w.getReviewStage()
                        + ", next " + w.getNextReviewAt() + ")")
                .toList());
        boolean anyWeak = all.stream()
                .filter(e -> AgentEvent.SCORE.equals(e.type()))
                .anyMatch(e -> e.get("score") instanceof Integer s && s <= 3);
        if (anyWeak) {
            assertFalse(queue.isEmpty(), "本场有 ≤3 分的轮次，错题本应有记录");
            assertTrue(queue.stream().allMatch(w -> w.getNextReviewAt() != null),
                    "每条错题都要排好下次复习时间");
        }
    }
}
