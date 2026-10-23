package com.bupt.mockinterview.llm.chat;

import com.bupt.mockinterview.llm.LlmException;
import com.bupt.mockinterview.llm.ToolCall;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 两个 OpenAI 兼容通道共用的工具响应校验，避免丢弃或静默修复格式错误的调用。 */
final class ToolCallParser {

    private ToolCallParser() {
    }

    static List<ToolCall> parse(JsonNode message, ObjectMapper mapper) {
        JsonNode calls = message.path("tool_calls");
        if (calls.isMissingNode() || calls.isNull()) {
            return List.of();
        }
        if (!calls.isArray()) {
            throw new LlmException("模型 tool_calls 必须是数组");
        }
        List<ToolCall> result = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (JsonNode call : calls) {
            JsonNode function = call.path("function");
            String id = requiredText(call.path("id"), "id");
            String type = requiredText(call.path("type"), "type");
            String name = requiredText(function.path("name"), "function.name");
            String arguments = requiredText(function.path("arguments"), "function.arguments");
            if (!"function".equals(type) || !ids.add(id)) {
                throw new LlmException("模型 tool_calls 类型无效或 id 重复");
            }
            try {
                JsonNode parsed = mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                        .readTree(arguments);
                if (parsed == null || !parsed.isObject()) {
                    throw new LlmException("模型 tool_calls function.arguments 必须是 JSON 对象字符串");
                }
            } catch (LlmException e) {
                throw e;
            } catch (Exception e) {
                throw new LlmException("模型 tool_calls function.arguments 不是有效 JSON", e);
            }
            result.add(new ToolCall(id, name, arguments));
        }
        return List.copyOf(result);
    }

    private static String requiredText(JsonNode node, String field) {
        if (!node.isTextual() || node.textValue().isBlank()) {
            throw new LlmException("模型 tool_calls 缺少有效字符串字段 " + field);
        }
        return node.textValue();
    }
}
