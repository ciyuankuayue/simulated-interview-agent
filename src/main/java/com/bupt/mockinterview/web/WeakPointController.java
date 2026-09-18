package com.bupt.mockinterview.web;

import com.bupt.mockinterview.domain.WeakPoint;
import com.bupt.mockinterview.domain.repository.WeakPointRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 错题本（api.md §5）：按 next_review_at 升序返回待复习的薄弱点。
 *
 * 注：T8（memory 模块）负责**写入**错题本（判分 ≤3 时落 weak_point），
 * 这里只做查询视图，不产生数据。
 */
@RestController
@RequestMapping("/api/weak-point")
public class WeakPointController {

    private final WeakPointRepository repo;

    public WeakPointController(WeakPointRepository repo) {
        this.repo = repo;
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        List<WeakPoint> all = new ArrayList<>(repo.findAll());
        all.sort(Comparator.comparing(w -> w.getNextReviewAt(),
                Comparator.nullsLast(Comparator.naturalOrder())));

        List<Map<String, Object>> resp = new ArrayList<>();
        for (WeakPoint w : all) {
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
