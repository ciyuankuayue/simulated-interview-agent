package com.bupt.mockinterview.web;

import com.bupt.mockinterview.domain.WeakPoint;
import com.bupt.mockinterview.memory.WeakPointMemoryService;
import org.springframework.web.bind.annotation.GetMapping;
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
 * 错题本（api.md §5）：按 next_review_at 升序返回待复习的薄弱点。
 *
 * 写入在 T8 完成——{@link WeakPointMemoryService} 在每轮判分后落 weak_point
 * （≤3 分记/升阶，≥6 分出队），这里只做查询视图。
 */
@RestController
@RequestMapping("/api/weak-point")
public class WeakPointController {

    private final WeakPointMemoryService memory;

    public WeakPointController(WeakPointMemoryService memory) {
        this.memory = memory;
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

    private static List<Map<String, Object>> render(List<WeakPoint> points) {
        List<Map<String, Object>> resp = new ArrayList<>();
        for (WeakPoint w : points) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("weakPointId", w.getId());
            m.put("knowledgePoint", w.getKnowledgePoint());
            m.put("wrongCount", w.getWrongCount());
            m.put("reviewStage", w.getReviewStage());
            m.put("nextReviewAt", w.getNextReviewAt() == null ? null : w.getNextReviewAt().toString());
            m.put("status", w.getStatus());
            resp.add(m);
        }
        return resp;
    }
}
