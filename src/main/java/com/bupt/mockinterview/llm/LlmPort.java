package com.bupt.mockinterview.llm;

import com.bupt.mockinterview.llm.chat.ModelRole;

import java.util.List;
import java.util.Map;

/**
 * LLM 能力端口（T6 引入）：agent 编排层只依赖这个接口，不依赖具体厂商实现。
 *
 * 目的只有一个：**让 T6 的编排逻辑可以离线单测**（用返回预设结果的 stub 实现，
 * 不消耗 token、不依赖网络）。生产实现是 {@link LlmService}。
 *
 * R3-1：chat 系列首参从具体模型名改为逻辑角色 {@link ModelRole}，
 * 厂商模型名由 ModelRouter 按用户选择解析，调用方不再感知 DeepSeek/通义模型串。
 */
public interface LlmPort {

    /** 实时侧：非思考模式，返回一句追问 / 纠正 / 新题。 */
    LlmReply followUp(String system, String user);

    /** 判分：思考模式 + JSON 输出，返回结构化结果（4 维 × 0-2 = 0-8）。 */
    ScoreResult score(String question, String answer);

    /**
     * 通用 chat，供 Critic 报告等需要自定义 prompt 的场景。
     *
     * @param role      逻辑模型角色（FAST 非思考档 / REASONER 思考档），具体模型由路由解析
     * @param jsonMode  true 时强制模型输出 JSON（对应 {@code response_format: json_object}）
     * @param maxTokens 生成 token 上限；思考模式下限制 reasoning+content 总预算，
     *                  设太小会被 reasoning 挤爆导致 JSON 截断（见 CriticService）
     */
    LlmReply chat(ModelRole role, boolean thinking, List<Map<String, Object>> messages,
                  List<Map<String, Object>> tools, boolean jsonMode, int maxTokens);

    /**
     * R2：带计费场景的 chat（用量流水按 scene 归类）。默认实现委托无场景重载（离线 stub / 不计费），
     * 生产实现 {@link LlmService} 覆盖它做预检与实扣。
     */
    default LlmReply chat(ModelRole role, boolean thinking, List<Map<String, Object>> messages,
                          List<Map<String, Object>> tools, boolean jsonMode, int maxTokens,
                          com.bupt.mockinterview.billing.UsageScene scene) {
        return chat(role, thinking, messages, tools, jsonMode, maxTokens);
    }

    /**
     * 通用 chat（使用全局 {@code max-tokens}），供不需要自定义预算的场景。
     */
    LlmReply chat(ModelRole role, boolean thinking, List<Map<String, Object>> messages,
                  List<Map<String, Object>> tools, boolean jsonMode);

    /**
     * R1-3：校验一个「待保存」的用户自带 DeepSeek key（GET /models，不烧对话 token、不扣点）。
     * 离线 stub 默认抛 UnsupportedOperationException（只有 UserController 的真实验活路径用到）。
     */
    default ApiKeyStatus verifyApiKey(String candidateKey) {
        throw new UnsupportedOperationException("该 LlmPort 实现不支持 key 验活");
    }
}
