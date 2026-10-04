package com.bupt.mockinterview.llm.chat;

/**
 * 对话调用的逻辑模型角色（R3-1）：调用方只表达"要哪一档"，
 * 具体厂商模型名由 {@link ModelRouter} 按用户选择 + 厂商配置映射。
 *
 * <ul>
 *   <li>{@link #FAST}：非思考实时档——追问、JD 解析（快、便宜）。</li>
 *   <li>{@link #REASONER}：思考档——判分、Critic 报告（要推理 + 大 JSON 预算）。</li>
 * </ul>
 */
public enum ModelRole {
    FAST,
    REASONER
}
