package com.bupt.mockinterview.agent;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * agent 层产出的事件，对齐 api.md §4 的 SSE 事件协议。
 *
 * T6 只生产事件、不关心传输；T7 的 SSE controller 负责把它推给前端。
 * 这样编排逻辑可离线单测（直接断言事件序列），不必起 Web 容器。
 */
public record AgentEvent(
        /** 事件类型：question / score / followup / await_answer / report / done / error */
        String type,
        /** 事件负载，字段名对齐 api.md §4 表格。 */
        Map<String, Object> data
) {
    public static final String QUESTION = "question";
    public static final String SCORE = "score";
    public static final String FOLLOWUP = "followup";
    public static final String AWAIT_ANSWER = "await_answer";
    public static final String REPORT = "report";
    public static final String DONE = "done";
    public static final String ERROR = "error";

    /** 变长 kv 构造，保持插入顺序便于断言与序列化稳定。 */
    public static AgentEvent of(String type, Object... kv) {
        Map<String, Object> data = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            data.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return new AgentEvent(type, data);
    }

    public AgentEvent put(String key, Object value) {
        data.put(key, value);
        return this;
    }

    public Object get(String key) {
        return data.get(key);
    }
}
