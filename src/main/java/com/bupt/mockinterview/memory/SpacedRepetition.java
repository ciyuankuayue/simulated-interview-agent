package com.bupt.mockinterview.memory;

import java.time.Duration;
import java.time.Instant;

/**
 * 间隔重复排期（domain.md §5 **方案 A：固定阶梯**）——MVP 不做 Leitner / SM-2。
 *
 * 规则：
 * <pre>
 * review_stage ∈ {0,1,2,3} → 间隔 [1, 3, 7, 15] 天
 * 再次答崩：stage = min(stage + 1, 3)，wrong_count + 1，按新 stage 重排 next_review_at
 * 答对（本轮 score &gt;= 6）：标记 resolved，不再进入复习队列
 * </pre>
 *
 * 设计取舍：固定阶梯是**可预测的**（用户能自己算出来什么时候该复习什么），
 * 而 SM-2 需要每次自评难度、冷启动时参数敏感；MVP 阶段先跑通回路，
 * 真出现「15 天还忘」的样本再评估升级（domain.md §5 已注明）。
 */
public final class SpacedRepetition {

    /** review_stage → 间隔天数（索引即 stage）。 */
    public static final int[] INTERVAL_DAYS = {1, 3, 7, 15};

    /** 最高阶梯（stage 封顶，防止无限拉长间隔）。 */
    public static final int MAX_STAGE = INTERVAL_DAYS.length - 1;

    /** domain.md §5：本轮 ≥ 6 分视为答对，从复习队列移除。 */
    public static final int MASTERED_SCORE = 6;

    /** domain.md §4：0-3 分（C/D）才记错题本。 */
    public static final int WEAK_SCORE_MAX = 3;

    private SpacedRepetition() {
    }

    /** 按阶梯算下次复习时间：{@code from + INTERVAL_DAYS[stage]} 天。 */
    public static Instant nextReviewAt(int stage, Instant from) {
        return from.plus(Duration.ofDays(INTERVAL_DAYS[clamp(stage)]));
    }

    /** 再次答崩后升一阶（封顶 MAX_STAGE）。 */
    public static int escalate(int stage) {
        return Math.min(Math.max(stage, 0) + 1, MAX_STAGE);
    }

    /** stage 落到合法区间 [0, MAX_STAGE]，防御脏数据。 */
    public static int clamp(int stage) {
        if (stage < 0) {
            return 0;
        }
        return Math.min(stage, MAX_STAGE);
    }

    /** 是否到了该复习的时间（已过 next_review_at）。 */
    public static boolean isDue(Instant nextReviewAt, Instant now) {
        return nextReviewAt != null && !nextReviewAt.isAfter(now);
    }
}
