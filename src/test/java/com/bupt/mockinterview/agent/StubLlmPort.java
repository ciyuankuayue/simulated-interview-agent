package com.bupt.mockinterview.agent;

import com.bupt.mockinterview.llm.LlmPort;
import com.bupt.mockinterview.llm.LlmReply;
import com.bupt.mockinterview.llm.ScoreResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 测试用 LLM 桩：按预设序列返回题目/判分/Critic JSON，不联网、不耗 token。
 *
 * 目的是让 T6 的编排状态机可以**离线断言事件序列**，
 * 同时能真实验证 Critic 报告的 JSON 解析路径（不是 mock 掉解析）。
 */
public class StubLlmPort implements LlmPort {

    /** followUp 依次返回的文本。 */
    public final List<String> questions = new ArrayList<>();
    /** score 依次返回的结果。 */
    public final List<ScoreResult> scores = new ArrayList<>();
    /** chat 返回的原文（Critic 报告 JSON）。 */
    public String criticJson = "{}";

    /** 记录每次调用收到的 user prompt，便于断言 prompt 是否带了 JD/历史。 */
    public final List<String> userPrompts = new ArrayList<>();

    private int qIdx;
    private int sIdx;

    public StubLlmPort() {
    }

    public StubLlmPort(List<String> questions, List<ScoreResult> scores, String criticJson) {
        this.questions.addAll(questions);
        this.scores.addAll(scores);
        this.criticJson = criticJson;
    }

    @Override
    public LlmReply followUp(String system, String user) {
        userPrompts.add(user);
        if (qIdx >= questions.size()) {
            return new LlmReply("（预设题目已用完）", null);
        }
        return new LlmReply(questions.get(qIdx++), null);
    }

    @Override
    public ScoreResult score(String question, String answer) {
        if (sIdx >= scores.size()) {
            return new ScoreResult(8, Map.of(), List.of(), "");
        }
        return scores.get(sIdx++);
    }

    @Override
    public LlmReply chat(String model, boolean thinking, List<Map<String, Object>> messages,
                         List<Map<String, Object>> tools, boolean jsonMode) {
        return chat(model, thinking, messages, tools, jsonMode, 0);
    }

    @Override
    public LlmReply chat(String model, boolean thinking, List<Map<String, Object>> messages,
                         List<Map<String, Object>> tools, boolean jsonMode, int maxTokens) {
        if (messages != null && !messages.isEmpty()) {
            Object last = messages.get(messages.size() - 1).get("content");
            userPrompts.add(String.valueOf(last));
        }
        return new LlmReply(criticJson, null);
    }
}
