package com.bupt.mockinterview.agent;

import com.bupt.mockinterview.llm.LlmException;
import com.bupt.mockinterview.llm.LlmPort;
import com.bupt.mockinterview.llm.LlmReply;
import com.bupt.mockinterview.llm.LlmService;
import com.bupt.mockinterview.llm.chat.ModelRole;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** 通考小师：知识答疑与学习教练。独立于小暖，所有模型调用仍走计费端口。 */
@Service
public class StudyCoachService {
    private static final int HISTORY_MESSAGES = 16;
    private static final long IDLE_TTL_MS = Duration.ofMinutes(30).toMillis();
    private static final String SYSTEM = """
            你是「通考小师」，用户专属的 AI 备考教练，负责知识答疑、拆解学习难点和给出可执行的练习方向。
            开场身份：我是通考小师，你的专属备考教练。请放心将知识疑问与学习困惑交给我，我们一起把难点弄懂。
            回答要求：
            1. 以用户目标岗位、问题和基础为依据。知识题先给清楚结论，再解释关键原理，配一个短例子，
               最后可给一个自检问题。默认简明，通常 200-400 字；按用户要求可展开。不要每轮重复自我介绍。
            2. 学习路径先判断用户提供的岗位、基础、可用时间；信息不足时只问一个关键澄清问题。
               有足够信息后建议 1-3 项可以实际完成的练习，不一上来堆满课程或长计划。
            3. 不得编造用户经历、简历内容、报告、分数或学习进度。你只能看到本次对话里用户发送的内容，
               没有自动读取资料库或报告；没有看到的就明确说没有看到，可以请用户贴相关片段。
            4. 区分确定的知识与需要核实的信息；不确定时坦诚说明，不伪造出处。
            5. 温和具体地鼓励，避免保证通过面试。用户焦虑时简短关照，可建议找「陪考小暖」聊聊，
               也鼓励向现实中信任的人寻求支持；不做心理诊断或治疗建议。
            6. 不承诺永久记忆、自动提醒、日程管理或尚未实现的功能。你是学习支持工具，帮助用户自己掌握知识。
            """;

    private static final class Conversation {
        private final AtomicBoolean busy = new AtomicBoolean();
        private volatile List<Map<String, Object>> messages = List.of();
        private volatile long touchedAt = System.currentTimeMillis();
    }

    private final LlmPort llm;
    private final Map<Long, Conversation> conversations = new ConcurrentHashMap<>();

    public StudyCoachService(LlmPort llm) {
        this.llm = llm;
    }

    public Map<String, Object> chat(Long userId, String message) {
        if (userId == null) throw new IllegalStateException("小师对话缺少用户身份");
        long now = System.currentTimeMillis();
        conversations.entrySet().removeIf(entry ->
                !entry.getValue().busy.get() && now - entry.getValue().touchedAt > IDLE_TTL_MS);
        Conversation conversation = conversations.computeIfAbsent(userId, ignored -> new Conversation());
        if (!conversation.busy.compareAndSet(false, true)) {
            throw new IllegalStateException("小师正在回复上一条问题，请稍候再发");
        }
        try {
            List<Map<String, Object>> messages = new ArrayList<>();
            messages.add(LlmService.sys(SYSTEM));
            messages.addAll(conversation.messages);
            messages.add(LlmService.userMsg(message));
            LlmReply reply = llm.chat(ModelRole.FAST, false, messages, null, false);
            if (reply == null || reply.content() == null || reply.content().isBlank()) {
                throw new LlmException("小师这次未生成有效回答，请稍后重试");
            }
            List<Map<String, Object>> updated = new ArrayList<>(conversation.messages);
            updated.add(LlmService.userMsg(message));
            updated.add(LlmService.assistantMsg(reply.content()));
            if (updated.size() > HISTORY_MESSAGES) {
                updated = new ArrayList<>(updated.subList(updated.size() - HISTORY_MESSAGES, updated.size()));
            }
            // reset removes the Conversation object; late replies then update only this detached object.
            conversation.messages = List.copyOf(updated);
            return Map.of("reply", reply.content());
        } finally {
            conversation.touchedAt = System.currentTimeMillis();
            conversation.busy.set(false);
        }
    }

    public void reset(Long userId) {
        if (userId == null) throw new IllegalStateException("小师对话缺少用户身份");
        conversations.remove(userId);
    }
}
