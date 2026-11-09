package com.bupt.mockinterview.agent;

import com.bupt.mockinterview.domain.*;
import com.bupt.mockinterview.domain.repository.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Opt-in, real-provider, synthetic multi-case measurement. Never runs in the default test suite. */
@EnabledIfSystemProperty(named = "agent.eval.live", matches = "true")
@EnabledIfEnvironmentVariable(named = "DEEPSEEK_API_KEY", matches = ".+")
@SpringBootTest(properties = {"app.interview.agent-enabled=true", "app.interview.max-model-calls=3",
        "spring.datasource.url=jdbc:h2:mem:agent_eval_live;MODE=MySQL;DB_CLOSE_DELAY=-1"})
class AgentEvaluationLiveTest {
    record EvalCase(String id, String jobType, String jd, String resume, List<String> roles, String answer) {}

    @Autowired InterviewOrchestrator orchestrator;
    @Autowired InterviewSessionRepository sessions;
    @Autowired InterviewTurnRepository turns;
    @Autowired JdProfileRepository jds;
    @Autowired ResumeVersionRepository resumes;
    @Autowired CriticReportRepository reports;
    @Autowired LlmUsageLogRepository usage;
    @Autowired User testUser;
    @Autowired ObjectMapper mapper;

    @Test
    void threeSyntheticCasesWithActualModelAndReport() throws Exception {
        Path output = Path.of("target", "agent-eval-live-2026-11-03.json");
        List<Map<String, Object>> rows = new ArrayList<>();
        List<EvalCase> cases = List.of(
                new EvalCase("python-junior-unknown", "Python 初级后端",
                        "Python 初级后端，考察 asyncio 任务调度、取消和异常处理，以及本人贡献。",
                        "【合成资料】做过 Python 异步报表练习，负责调度和日志；没有线上指标或生产事故。",
                        List.of("技术深挖官"),
                        "这部分机制我不熟悉，无法准确解释。我做过调度和日志，但没有线上指标。"),
                new EvalCase("java-mid-project", "Java 中级后端",
                        "Java 中级后端，考察库存一致性、MySQL 条件更新、个人贡献和验证方法。",
                        "【合成资料】课程订单项目中负责库存扣减接口，用 MySQL 条件 UPDATE 防止库存扣成负数；其他模块由队友实现，没有线上压测或故障经历。",
                        List.of("项目质疑官"),
                        "我们用数据库保证库存一致性，我参与了这个模块。具体怎么验证的，我还没有说清楚。"),
                new EvalCase("java-mid-behavior", "Java 中级后端",
                        "Java 中级后端，重视跨团队沟通、冲突解决和复盘反思。",
                        "【合成资料】课程项目临近交付时与前端同学对接口字段有分歧；没有记录本人具体行动或量化结果。",
                        List.of("行为面试官"),
                        "当时双方意见不同，后来继续讨论了。我还没有讲清楚我本人做了什么，结果也没有量化。")
        );
        for (EvalCase c : cases) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", c.id()); row.put("jobType", c.jobType()); row.put("jd", c.jd());
            row.put("selectedResume", c.resume()); row.put("roles", c.roles()); row.put("answer", c.answer());
            row.put("startedUtc", Instant.now().toString());
            long before = usage.findByUserIdOrderByIdDesc(testUser.getId(), PageRequest.of(0, 1)).stream()
                    .mapToLong(LlmUsageLog::getId).max().orElse(0);
            long started = System.nanoTime();
            try {
                JdProfile jd = new JdProfile(); jd.setUser(testUser); jd.setJobType(c.jobType()); jd.setRawText(c.jd());
                jds.saveAndFlush(jd);
                ResumeVersion resume = new ResumeVersion(); resume.setUser(testUser); resume.setContent(c.resume());
                resumes.saveAndFlush(resume);
                var start = orchestrator.start(jd.getId(), c.roles(), 1, resume.getId());
                row.put("firstQuestionMs", (System.nanoTime() - started) / 1_000_000.0);
                row.put("firstQuestion", start.firstQuestion());
                long answerStarted = System.nanoTime();
                var events = orchestrator.answer(start.sessionId(), c.answer());
                row.put("answerMs", (System.nanoTime() - answerStarted) / 1_000_000.0);
                row.put("events", events.stream().map(AgentEvent::type).toList());
                if (sessions.findByIdAndUserId(start.sessionId(), testUser.getId()).orElseThrow().getStatus() != SessionStatus.DONE) {
                    long endStarted = System.nanoTime();
                    orchestrator.end(start.sessionId());
                    row.put("endMs", (System.nanoTime() - endStarted) / 1_000_000.0);
                }
                CriticReport report = reports.findBySessionIdAndUser(start.sessionId(), testUser).orElse(null);
                row.put("reportGenerated", report != null && report.getMatchScore() != null);
                if (report != null) {
                    row.put("matchScore", report.getMatchScore());
                    row.put("overallNotes", report.getOverallNotes());
                    row.put("perTurnReview", report.getPerTurnReview());
                }
                row.put("turns", turns.findBySessionIdOrderBySeqAsc(start.sessionId()).stream().map(t -> {
                    Map<String, Object> turn = new LinkedHashMap<>();
                    turn.put("seq", t.getSeq()); turn.put("question", t.getQuestion());
                    turn.put("answer", t.getAnswer()); turn.put("metadata", t.getAgentMetadata());
                    return turn;
                }).toList());
                row.put("success", report != null && report.getMatchScore() != null);
            } catch (Exception e) {
                row.put("success", false);
                row.put("errorType", e.getClass().getSimpleName());
                row.put("errorMessage", e.getMessage());
            } finally {
                var calls = usage.findByUserIdOrderByIdDesc(testUser.getId(), PageRequest.of(0, 100)).stream()
                        .filter(item -> item.getId() > before).toList();
                row.put("calls", calls.stream().map(item -> Map.of("scene", item.getScene().name(),
                        "model", item.getModel(), "tokens", item.getTotalTokens())).toList());
                row.put("totalTokens", calls.stream().mapToLong(LlmUsageLog::getTotalTokens).sum());
                row.put("totalMs", (System.nanoTime() - started) / 1_000_000.0);
                rows.add(row);
                Files.writeString(output, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(rows), StandardCharsets.UTF_8);
            }
        }
        assertEquals(3, rows.stream().filter(row -> Boolean.TRUE.equals(row.get("success"))).count(),
                "All three cases must have an actual report; raw failures remain in the artifact");
    }
}
