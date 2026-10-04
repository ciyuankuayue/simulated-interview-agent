package com.bupt.mockinterview.llm.chat;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 厂商无关的对话响应（R3-1）。
 *
 * @param content   正文（思考模式下仍必须非空，由上层校验）
 * @param reasoning 思维链（无思考时为 null）
 * @param usage     厂商原始 usage 节点（prompt/completion/total tokens）；
 *                  计费解析仍在 LlmService，缺失时由上层按字符估算兜底
 */
public record ChatResponse(String content, String reasoning, JsonNode usage) {
}
