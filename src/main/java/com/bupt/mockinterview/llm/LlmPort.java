package com.bupt.mockinterview.llm;

import java.util.List;
import java.util.Map;

/**
 * LLM 能力端口（T6 引入）：agent 编排层只依赖这个接口，不依赖具体 DeepSeek 实现。
 *
 * 目的只有一个：**让 T6 的编排逻辑可以离线单测**（用返回预设结果的 stub 实现，
 * 不消耗 token、不依赖网络）。生产实现是 {@link LlmService}。
 */
public interface LlmPort {

    /** 实时侧：非思考模式，返回一句追问 / 纠正 / 新题。 */
    LlmReply followUp(String system, String user);

    /** 判分：思考模式 + JSON 输出，返回结构化结果（4 维 × 0-2 = 0-8）。 */
    ScoreResult score(String question, String answer);

    /**
     * 通用 chat，供 Critic 报告等需要自定义 prompt 的场景。
     *
     * @param jsonMode  true 时强制模型输出 JSON（对应 {@code response_format: json_object}）
     * @param maxTokens 生成 token 上限；思考模式下限制 reasoning+content 总预算，
     *                  设太小会被 reasoning 挤爆导致 JSON 截断（见 CriticService）
     */
    LlmReply chat(String model, boolean thinking, List<Map<String, Object>> messages,
                  List<Map<String, Object>> tools, boolean jsonMode, int maxTokens);

    /**
     * 通用 chat（使用全局 {@code max-tokens}），供不需要自定义预算的场景。
     */
    LlmReply chat(String model, boolean thinking, List<Map<String, Object>> messages,
                  List<Map<String, Object>> tools, boolean jsonMode);
}
