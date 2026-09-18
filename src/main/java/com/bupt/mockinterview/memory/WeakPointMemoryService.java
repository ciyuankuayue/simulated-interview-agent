package com.bupt.mockinterview.memory;

import com.bupt.mockinterview.domain.JdProfile;
import com.bupt.mockinterview.domain.WeakPoint;
import com.bupt.mockinterview.domain.repository.WeakPointRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 错题本（memory 层，T8）：判分结果 → weak_point 的**读写闭环**。
 *
 * 写入时机（domain.md §4 / §5）：
 * <pre>
 * score ≤ 3（C/D）  → 记错题本：命中已有知识点则升阶 + wrong_count+1，否则新建（stage=0, 1 天后复习）
 * score 4-5（B）    → 不动（不够好，但也没到「崩」，不占用复习队列）
 * score ≥ 6（A/S）  → 命中已有知识点则标记 resolved（间隔重复的「答对即出队」）
 * </pre>
 *
 * 知识点从哪来：判分模型顺带输出的 {@code knowledge_point}（{@link com.bupt.mockinterview.llm.ScoreResult#knowledgePoint()}），
 * 10-20 字的稳定短标签。**不用 missed_points 凑 key**——措辞每次都漂，
 * 同一知识点会被记成好几条，间隔重复就失去意义。
 *
 * 匹配策略：归一化后精确相等，或一方包含另一方（较短一方 ≥ 4 字才认，防误伤）。
 * 因为同一个知识点在不同场次可能被模型写成「MySQL 索引失效场景」/「索引失效的典型场景」，
 * 只做精确匹配会把错题本打散。
 */
@Service
public class WeakPointMemoryService {

    /** status 字段（现状）最大长度，防止 LLM 输出过长污染错题本。 */
    private static final int STATUS_MAX = 200;

    private static final Pattern NOISE = Pattern.compile("[^\\p{L}\\p{N}]");

    /**
     * 中文虚词：归一化时一并去掉。
     * 同一个知识点在不同场次常被写成「索引失效场景」/「索引失效的场景」，
     * 不去掉「的」就对不上，错题本会被打散成两条。
     */
    private static final Pattern STOP_WORDS = Pattern.compile("[的了和与及是在有]");

    private final WeakPointRepository repo;

    public WeakPointMemoryService(WeakPointRepository repo) {
        this.repo = repo;
    }

    /** 一次判分后的结果，便于调用方（T6 编排）记录与断言。 */
    public record Outcome(int created, int escalated, int resolved, List<Long> weakPointIds) {

        public boolean isEmpty() {
            return created == 0 && escalated == 0 && resolved == 0;
        }
    }

    /**
     * 每轮判分后的统一入口：记崩 / 升阶 / 出队，三选一。
     *
     * @param jd             所属 JD（可空，错题本按 JD 维度排）
     * @param knowledgePoint 判分模型给的核心知识点标签，空则本轮不处理
     * @param score           本轮得分（0-8）
     * @param status          「现状」摘要（通常取漏掉的关键点），超长自动截断
     */
    @Transactional
    public Outcome onTurnScored(JdProfile jd, String knowledgePoint, int score, String status, Instant now) {
        String kp = knowledgePoint == null ? "" : knowledgePoint.trim();
        if (kp.isEmpty()) {
            return new Outcome(0, 0, 0, List.of());
        }
        List<WeakPoint> hits = findAlive(jd, kp);

        if (score >= SpacedRepetition.MASTERED_SCORE) {
            List<Long> ids = new ArrayList<>();
            for (WeakPoint w : hits) {
                w.setResolved(true);
                w.setResolvedAt(now);
                repo.save(w);
                ids.add(w.getId());
            }
            return new Outcome(0, 0, ids.size(), ids);
        }

        if (score <= SpacedRepetition.WEAK_SCORE_MAX) {
            String st = truncate(status);
            List<Long> ids = new ArrayList<>();
            if (!hits.isEmpty()) {
                WeakPoint w = hits.get(0);
                w.setReviewStage(SpacedRepetition.escalate(w.getReviewStage() == null ? 0 : w.getReviewStage()));
                w.setWrongCount((w.getWrongCount() == null ? 0 : w.getWrongCount()) + 1);
                w.setNextReviewAt(SpacedRepetition.nextReviewAt(w.getReviewStage(), now));
                if (st != null) {
                    w.setStatus(st);
                }
                WeakPoint saved = repo.save(w);
                ids.add(saved.getId());
                return new Outcome(0, 1, 0, ids);
            }
            WeakPoint fresh = new WeakPoint();
            fresh.setKnowledgePoint(kp);
            fresh.setWrongCount(1);
            fresh.setReviewStage(0);
            fresh.setNextReviewAt(SpacedRepetition.nextReviewAt(0, now));
            fresh.setStatus(st);
            fresh.setJdProfile(jd);
            fresh.setResolved(false);
            fresh.setCreatedAt(now);
            ids.add(repo.save(fresh).getId());
            return new Outcome(1, 0, 0, ids);
        }

        return new Outcome(0, 0, 0, List.of());
    }

    /** 复习队列：未出队的，按 next_review_at 升序（api.md §5）。 */
    @Transactional(readOnly = true)
    public List<WeakPoint> queue() {
        return sorted(repo.findByResolvedFalse());
    }

    /** 今日待复习：已到期（next_review_at &lt;= now）且未出队。 */
    @Transactional(readOnly = true)
    public List<WeakPoint> due(Instant now) {
        return sorted(repo.findByResolvedFalseAndNextReviewAtLessThanEqual(now));
    }

    // ---------- 匹配 ----------

    /**
     * 找同一 JD 下未出队且知识点相同的记录。
     * JD 为空的记录（历史数据）也参与匹配，避免升级后老数据变成孤儿。
     */
    private List<WeakPoint> findAlive(JdProfile jd, String knowledgePoint) {
        String target = normalize(knowledgePoint);
        Long jdId = jd == null ? null : jd.getId();
        List<WeakPoint> hits = new ArrayList<>();
        for (WeakPoint w : repo.findByResolvedFalse()) {
            if (w.getKnowledgePoint() == null) {
                continue;
            }
            Long wid = w.getJdProfile() == null ? null : w.getJdProfile().getId();
            boolean sameJd = jdId == null || wid == null || jdId.equals(wid);
            if (sameJd && sameTopic(target, normalize(w.getKnowledgePoint()))) {
                hits.add(w);
            }
        }
        return hits;
    }

    /** 归一化：去标点/空白 → 去中文虚词 → 小写，让「索引失效场景」和「索引失效的场景」能对上。 */
    private static String normalize(String s) {
        String noNoise = NOISE.matcher(s == null ? "" : s).replaceAll("");
        return STOP_WORDS.matcher(noNoise).replaceAll("").toLowerCase(Locale.ROOT);
    }

    /** 精确相等，或一方包含另一方（较短一方至少 4 字，避免「锁」这种单字误伤一片）。 */
    private static boolean sameTopic(String a, String b) {
        if (a.isEmpty() || b.isEmpty()) {
            return false;
        }
        if (a.equals(b)) {
            return true;
        }
        int min = Math.min(a.length(), b.length());
        return min >= 4 && (a.contains(b) || b.contains(a));
    }

    private static List<WeakPoint> sorted(List<WeakPoint> list) {
        List<WeakPoint> copy = new ArrayList<>(list);
        copy.sort(Comparator.comparing(w -> w.getNextReviewAt(),
                Comparator.nullsLast(Comparator.naturalOrder())));
        return copy;
    }

    private static String truncate(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        String t = s.trim();
        return t.length() <= STATUS_MAX ? t : t.substring(0, STATUS_MAX) + "…";
    }
}
