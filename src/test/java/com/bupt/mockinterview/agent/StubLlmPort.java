package com.bupt.mockinterview.agent;

import com.bupt.mockinterview.llm.LlmPort;
import com.bupt.mockinterview.llm.LlmReply;
import com.bupt.mockinterview.llm.ScoreResult;
import com.bupt.mockinterview.llm.ScoreTrack;
import com.bupt.mockinterview.llm.chat.ModelRole;

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
    /** R7：记录每次 score 收到的判分赛道，供断言 TECH/BEHAVIOR 路由。 */
    public final List<ScoreTrack> scoreTracks = new ArrayList<>();
    /** chat 返回的原文（Critic 报告 JSON）。 */
    public String criticJson = "{}";

    /** R1-3：非 null 时对应方法直接抛（用于验证 402/409 穿透降级边界，而不是被降级吞掉）。 */
    public RuntimeException followUpError;
    public RuntimeException scoreError;
    public RuntimeException chatError;

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

    /** 恢复到构造后的初始序列并清零调用游标 / 异常注入（stub 是 context 级单例，测试方法间必须重置）。 */
    public void reset(List<String> initialQuestions, List<ScoreResult> initialScores, String initialCriticJson) {
        questions.clear();
        questions.addAll(initialQuestions);
        scores.clear();
        scores.addAll(initialScores);
        criticJson = initialCriticJson;
        userPrompts.clear();
        scoreTracks.clear();
        followUpError = null;
        scoreError = null;
        chatError = null;
        qIdx = 0;
        sIdx = 0;
    }

    @Override
    public LlmReply followUp(String system, String user) {
        if (followUpError != null) {
            throw followUpError;
        }
        userPrompts.add(user);
        if (qIdx >= questions.size()) {
            return new LlmReply("（预设题目已用完）", null);
        }
        return new LlmReply(questions.get(qIdx++), null);
    }

    @Override
    public ScoreResult score(ScoreTrack track, String question, String answer) {
        scoreTracks.add(track);
        if (scoreError != null) {
            throw scoreError;
        }
        if (sIdx >= scores.size()) {
            return new ScoreResult(8, Map.of(), List.of(), "");
        }
        return scores.get(sIdx++);
    }

    @Override
    public LlmReply chat(ModelRole role, boolean thinking, List<Map<String, Object>> messages,
                         List<Map<String, Object>> tools, boolean jsonMode) {
        return chat(role, thinking, messages, tools, jsonMode, 0);
    }

    @Override
    public LlmReply chat(ModelRole role, boolean thinking, List<Map<String, Object>> messages,
                         List<Map<String, Object>> tools, boolean jsonMode, int maxTokens) {
        if (chatError != null) {
            throw chatError;
        }
        if (messages != null && !messages.isEmpty()) {
            Object last = messages.get(messages.size() - 1).get("content");
            userPrompts.add(String.valueOf(last));
        }
        return new LlmReply(criticJson, null);
    }
}
