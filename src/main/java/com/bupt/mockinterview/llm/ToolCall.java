package com.bupt.mockinterview.llm;

/** 模型提出的一次 function calling 请求；参数保留原始 JSON 字符串，由 agent 校验并执行。 */
public record ToolCall(String id, String name, String arguments) {
}
