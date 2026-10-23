package com.bupt.mockinterview.llm.chat;

import com.bupt.mockinterview.llm.ToolCall;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * 厂商无关的对话响应（R3-1）。
 *
 * @param content   正文（工具调用轮可为空；最终回答仍由上层校验非空）
 * @param reasoning 思维链（无思考时为 null）
 * @param usage     厂商原始 usage 节点（prompt/completion/total tokens）；
 *                  计费解析仍在 LlmService，缺失时由上层按字符估算兜底
 * @param toolCalls 模型提出的工具请求；无工具时为不可变空列表
 */
public record ChatResponse(String content, String reasoning, JsonNode usage, List<ToolCall> toolCalls) {
    public ChatResponse {
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }

    public ChatResponse(String content, String reasoning, JsonNode usage) {
        this(content, reasoning, usage, List.of());
    }
}
