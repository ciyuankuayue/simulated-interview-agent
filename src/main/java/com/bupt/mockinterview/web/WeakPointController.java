package com.bupt.mockinterview.web;

import com.bupt.mockinterview.domain.CriticReport;
import com.bupt.mockinterview.domain.InterviewTurn;
import com.bupt.mockinterview.domain.WeakPoint;
import com.bupt.mockinterview.domain.repository.CriticReportRepository;
import com.bupt.mockinterview.domain.repository.InterviewTurnRepository;
import com.bupt.mockinterview.memory.WeakPointMemoryService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 错题本（api.md §5）：待复习队列 + 今日到期 + 单条详情（T10 点开回看答崩现场）。
 *
 * 写入在 T8 完成——{@link WeakPointMemoryService} 在每轮判分后落 weak_point
 * （≤3 分记/升阶，≥6 分出队），这里只做查询视图。
 */
@RestController
@RequestMapping("/api/weak-point")
public class WeakPointController {

    private final WeakPointMemoryService memory;
    private final InterviewTurnRepository turnRepo;
    private final CriticReportRepository reportRepo;
    private final ObjectMapper mapper;

    public WeakPointController(WeakPointMemoryService memory, InterviewTurnRepository turnRepo,
                               CriticReportRepository reportRepo, ObjectMapper mapper) {
        this.memory = memory;
        this.turnRepo = turnRepo;
        this.reportRepo = reportRepo;
        this.mapper = mapper;
    }

    /** 复习队列（未出队），按 next_review_at 升序。 */
    @GetMapping
    public List<Map<String, Object>> list() {
        return render(memory.queue());
    }

    /**
     * 今日待复习：已到 next_review_at 且未出队（间隔重复的「今天该看哪些」）。
     *
     * @param now 可选，ISO-8601 时刻；不传取当前时间（便于测试与「假装今天是后天」）
     */
    @GetMapping("/due")
    public List<Map<String, Object>> due(@RequestParam(required = false) String now) {
        Instant at;
        try {
            at = (now == null || now.isBlank()) ? Instant.now() : Instant.parse(now);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("now 不是合法 ISO-8601 时刻: " + now);
        }
        return render(memory.due(at));
    }

    /**
     * 单条详情（T10）：错题本卡片点开后回看「最近一次答崩现场」——
     * 当时的原题/我的回答/层级/得分，外加该场 Critic 报告里对应轮次的
     * 讲解/纠正/知识补充/示范回答。lastSessionId 为空（旧数据）时 latest=null，前端降级。
     */
    @GetMapping("/{id}")
    public Map<String, Object> detail(@PathVariable Long id) {
        WeakPoint w = memory.require(id);
        Map<String, Object> body = renderOne(w);
        body.put("latest", latestScene(w));
        return body;
    }

    // ---------- 内部 ----------

    /** 聚合最近一次答崩现场；任何一环缺失都降级为 null，不阻断详情查看。 */
    private Map<String, Object> latestScene(WeakPoint w) {
        if (w.getLastSessionId() == null || w.getLastTurnSeq() == null) {
            return null;
        }
        InterviewTurn turn = turnRepo.findBySessionIdOrderBySeqAsc(w.getLastSessionId()).stream()
                .filter(t -> w.getLastTurnSeq().equals(t.getSeq()))
                .findFirst().orElse(null);
        if (turn == null) {
            return null;
        }
        Map<String, Object> scene = new LinkedHashMap<>();
        scene.put("sessionId", w.getLastSessionId());
        scene.put("turnSeq", turn.getSeq());
        scene.put("layer", turn.getLayer());
        scene.put("question", turn.getQuestion());
        scene.put("answer", turn.getAnswer());
        scene.put("score", extractScore(turn.getScoreJson()));
        scene.put("review", reviewOf(w.getLastSessionId(), w.getLastTurnSeq()));
        return scene;
    }

    /** 从该场 Critic 报告的 perTurnReview 里取对应轮次的四段讲解；报告未出/对不上返回 null。 */
    private Map<String, Object> reviewOf(Long sessionId, int turnSeq) {
        CriticReport report = reportRepo.findBySessionId(sessionId);
        if (report == null || report.getPerTurnReview() == null) {
            return null;
        }
        try {
            List<Map<String, Object>> reviews =
                    mapper.readValue(report.getPerTurnReview(), new TypeReference<List<Map<String, Object>>>() {});
            for (Map<String, Object> r : reviews) {
                Object seq = r.get("turnSeq");
                if (seq != null && Integer.parseInt(String.valueOf(seq)) == turnSeq) {
                    Map<String, Object> view = new LinkedHashMap<>();
                    view.put("explanation", r.get("explanation"));
                    view.put("correction", r.get("correction"));
                    view.put("knowledgeSupplement", r.get("knowledgeSupplement"));
                    view.put("exemplarAnswer", r.get("exemplarAnswer"));
                    return view;
                }
            }
        } catch (Exception ignored) {
            // 报告 JSON 异常时降级：现场仍有题/答/分，只是没有讲解
        }
        return null;
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

    private static List<Map<String, Object>> render(List<WeakPoint> points) {
        List<Map<String, Object>> resp = new ArrayList<>();
        for (WeakPoint w : points) {
            resp.add(renderOne(w));
        }
        return resp;
    }

    private static Map<String, Object> renderOne(WeakPoint w) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("weakPointId", w.getId());
        m.put("knowledgePoint", w.getKnowledgePoint());
        m.put("wrongCount", w.getWrongCount());
        m.put("reviewStage", w.getReviewStage());
        m.put("nextReviewAt", w.getNextReviewAt() == null ? null : w.getNextReviewAt().toString());
        m.put("status", w.getStatus());
        m.put("resolved", w.getResolved());
        m.put("createdAt", w.getCreatedAt() == null ? null : w.getCreatedAt().toString());
        m.put("lastSessionId", w.getLastSessionId());
        m.put("lastTurnSeq", w.getLastTurnSeq());
        return m;
    }
}
