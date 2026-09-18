package com.bupt.mockinterview.llm;

import java.util.List;
import java.util.Map;

/**
 * 判分结果，对应 domain.md §4 落到 {@code interview_turn.score_json} 的
 * {@code {score, breakdown, missed_points, followup}}。
 *
 * 4 维度 × 0-2 分 = 0-8：正确性 / 完整性 / 深度 / 实证。
 */
public record ScoreResult(
        int score,
        /** 4 维打分，key 为「正确性/完整性/深度/实证」，各 0-2。 */
        Map<String, Integer> breakdown,
        /** 回答漏掉的关键点。 */
        List<String> missedPoints,
        /** 下一句追问（score>=8 时为空串）。 */
        String followup,
        /**
         * 本题考察的核心知识点（10-20 字稳定短标签，如「MySQL 索引失效场景」）。
         * T8 错题本按它聚合做间隔重复——所以要求**措辞稳定可复用**，不是一句话总结。
         * 模型没给时为空串，错题本该轮跳过。
         */
        String knowledgePoint
) {

    /** 兼容构造器：没有知识点标签时按空串处理（离线测试 / 旧调用点不用改）。 */
    public ScoreResult(int score, Map<String, Integer> breakdown, List<String> missedPoints, String followup) {
        this(score, breakdown, missedPoints, followup, "");
    }

    /** 知识点标签（永不为 null）。 */
    public String knowledgePointOrEmpty() {
        return knowledgePoint == null ? "" : knowledgePoint.trim();
    }

    /** 追问层数（domain.md §4）：8=S 不追问｜6-7=A 1 层｜≤5=B/C/D 2 层封顶。 */
    public int followupDepth() {
        if (score >= 8) {
            return 0;
        }
        return score >= 6 ? 1 : 2;
    }

    /** 是否该记错题本（0-3 = C/D）。 */
    public boolean needWeakPoint() {
        return score <= 3;
    }
}
