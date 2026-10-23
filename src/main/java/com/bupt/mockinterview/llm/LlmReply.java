package com.bupt.mockinterview.llm;

import java.util.List;

/**
 * 一次 chat 的回复。
 *
 * ⚠️ 关键坑（官方 thinking_mode 文档）：思考模式下模型会额外返回思维链 {@code reasoning_content}。
 * 一旦请求携带了 {@code tools}，后续每一轮都必须把上一轮的 {@code reasoning_content} 完整回传，
 * 否则 API 直接返回 400。所以这里把它一并返回，供 T6 agent 编排多轮工具调用时使用。
 */
public record LlmReply(
        /** 最终回答（JSON 模式下即为结构化 JSON 文本）。 */
        String content,
        /** 思维链；非思考模式或模型未返回时为 null。 */
        String reasoningContent,
        /** 本次调用 token 用量（R2 计费）；非计费/测试桩路径可为 null。 */
        TokenUsage usage,
        /** 本轮请求执行的工具；没有工具时为空列表。 */
        List<ToolCall> toolCalls
) {
    public LlmReply {
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }

    public LlmReply(String content, String reasoningContent, TokenUsage usage) {
        this(content, reasoningContent, usage, List.of());
    }

    /** 不带用量的便捷构造（测试桩 / 内部默认实现用，usage=null）。 */
    public LlmReply(String content, String reasoningContent) {
        this(content, reasoningContent, null, List.of());
    }
}
