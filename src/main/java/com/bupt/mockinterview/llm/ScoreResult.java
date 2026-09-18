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
        String followup
) {

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
