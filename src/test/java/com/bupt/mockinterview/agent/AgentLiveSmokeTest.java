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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/** Explicit opt-in, one synthetic case; real provider calls incur platform usage. */
@EnabledIfSystemProperty(named = "agent.live", matches = "true")
@EnabledIfEnvironmentVariable(named = "DEEPSEEK_API_KEY", matches = ".+")
@SpringBootTest(properties = {"app.interview.agent-enabled=true", "app.interview.max-model-calls=3",
        "spring.datasource.url=jdbc:h2:mem:agent_live_smoke;MODE=MySQL;DB_CLOSE_DELAY=-1"})
class AgentLiveSmokeTest {
    @Autowired InterviewOrchestrator orchestrator;
    @Autowired InterviewSessionRepository sessions;
    @Autowired InterviewTurnRepository turns;
    @Autowired JdProfileRepository jds;
    @Autowired ResumeVersionRepository resumes;
    @Autowired CriticReportRepository reports;
    @Autowired LlmUsageLogRepository usage;
    @Autowired User testUser;
    @Autowired ObjectMapper mapper;

    @Test void realProviderMakesBoundedDecisionsAndProducesAnEvidenceConstrainedReport() throws Exception {
        long before = usage.findByUserIdOrderByIdDesc(testUser.getId(), PageRequest.of(0, 1)).stream()
                .mapToLong(LlmUsageLog::getId).max().orElse(0);
        JdProfile jd = new JdProfile(); jd.setUser(testUser); jd.setJobType("Python初级后端工程师");
        jd.setRawText("Python初级工程师，考察asyncio协程、任务取消和错误处理，能解释本人项目贡献；不要求Java技术栈。");
        jds.saveAndFlush(jd);
        ResumeVersion resume = new ResumeVersion(); resume.setUser(testUser);
        resume.setContent("【合成测试资料】我做过Python异步报表练习，负责调度和日志；没有线上高并发量级、性能提升百分比或生产事故经历。");
        resumes.saveAndFlush(resume);
        var start = orchestrator.start(jd.getId(), List.of("技术深挖官", "项目质疑官"), 1, resume.getId());
        assertFalse(start.firstQuestion().isBlank());
        var events = orchestrator.answer(start.sessionId(), "这部分机制我还不熟悉，无法准确解释；我只写过报表调度和日志，没有测量线上量级或性能提升。");
        assertFalse(events.stream().anyMatch(e -> "QUESTION_FAILED".equals(e.get("code"))), "Agent decision should be valid");
        assertTrue(events.stream().anyMatch(e -> List.of("followup", "question", "done").contains(e.type())));
        if (sessions.findByIdAndUserId(start.sessionId(), testUser.getId()).orElseThrow().getStatus() != SessionStatus.DONE) {
            orchestrator.end(start.sessionId());
        }
        CriticReport report = reports.findBySessionIdAndUser(start.sessionId(), testUser).orElseThrow();
        assertNotNull(report.getMatchScore(), "Live smoke requires an actual parsed report, not a placeholder");
        List<InterviewTurn> stored = turns.findBySessionIdOrderBySeqAsc(start.sessionId());
        var calls = usage.findByUserIdOrderByIdDesc(testUser.getId(), PageRequest.of(0, 100)).stream()
                .filter(row -> row.getId() > before).toList();
        Map<String, Object> artifact = new LinkedHashMap<>();
        artifact.put("scope", "one synthetic case; real DeepSeek; local H2/RAM index; not production quality evaluation");
        artifact.put("firstQuestion", start.firstQuestion());
        artifact.put("events", events.stream().map(AgentEvent::type).toList());
        artifact.put("turns", stored.stream().map(t -> Map.of("seq", t.getSeq(), "question", t.getQuestion(),
                "answer", t.getAnswer() == null ? "" : t.getAnswer(), "metadata", t.getAgentMetadata())).toList());
        artifact.put("report", Map.of("matchScore", report.getMatchScore(), "overallNotes", report.getOverallNotes(),
                "perTurnReview", report.getPerTurnReview()));
        artifact.put("usage", calls.stream().map(row -> Map.of("scene", row.getScene().name(), "model", row.getModel(),
                "tokens", row.getTotalTokens(), "charged", row.getChargedTokens())).toList());
        artifact.put("totalTokens", calls.stream().mapToLong(LlmUsageLog::getTotalTokens).sum());
        Files.writeString(Path.of("target/agent-live-smoke.json"), mapper.writerWithDefaultPrettyPrinter().writeValueAsString(artifact), StandardCharsets.UTF_8);
    }
}
