package com.bupt.mockinterview.llm.chat;

import java.util.List;
import java.util.Map;

/**
 * 厂商无关的对话请求（R3-1）。协议差异（如 DeepSeek 的 {@code thinking.type} vs
 * 通义的 {@code enable_thinking}）由各 {@link ChatProvider} 实现内部消化，
 * 不泄漏到 LlmService 计费层与 agent 编排层。
 *
 * @param model    已由 {@link ModelRouter} 解析好的厂商具体模型名
 * @param messages OpenAI 风格消息数组
 * @param thinking 是否开启思考模式
 * @param jsonMode 是否强制 JSON 输出
 * @param tools    function calling 工具定义；null/空表示不用
 * @param maxTokens 生成 token 上限（思考模式含 reasoning+content 总预算）
 */
public record ChatRequest(
        String model,
        List<Map<String, Object>> messages,
        boolean thinking,
        boolean jsonMode,
        List<Map<String, Object>> tools,
        int maxTokens
) {
}
