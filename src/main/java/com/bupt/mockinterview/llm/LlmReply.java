package com.bupt.mockinterview.llm;

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
        String reasoningContent
) {
}
