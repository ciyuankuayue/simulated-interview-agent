package com.bupt.mockinterview.llm;

/**
 * 判分赛道（R7）：决定 {@link LlmPort#score} 使用哪套 rubric 的 system prompt。
 * <ul>
 *   <li>{@link #TECH}：正确性 / 完整性 / 深度 / 实证（技术题，R1 起的既有四维）；</li>
 *   <li>{@link #BEHAVIOR}：STAR结构 / 行动细节 / 结果与反思 / 表达与抗压（行为面试官轮次）。</li>
 * </ul>
 * 两套量纲一致（4 维 × 0-2 = 0-8），追问层数（8=S/6-7=A/≤5=B）、错题本规则共用。
 */
public enum ScoreTrack {
    TECH,
    BEHAVIOR
}
