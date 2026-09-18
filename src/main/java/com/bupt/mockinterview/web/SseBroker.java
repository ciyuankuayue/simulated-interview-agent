package com.bupt.mockinterview.web;

import com.bupt.mockinterview.agent.AgentEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * SSE 事件中转（T7）：把 agent 层产出的 {@link AgentEvent} 推给订阅该会话的前端。
 *
 * 三个必须处理的现实问题：
 * 1. **订阅晚于生产**——前端是 `start` 拿到首题后才来连 `/events`，而首题事件已经产生了。
 *    所以每个会话保留一份有界历史，新订阅者先重放再接实时流，保证首题不丢。
 * 2. **多端/重连**——同一会话允许多个 emitter（刷新页面会产生新的），断开的自动摘除。
 * 3. **done 即收流**——收到 done 事件主动 complete，避免前端挂着一个永不断开的流。
 */
@Component
public class SseBroker {

    /** 单会话保留的历史事件上限（防止长时间面试无限堆积）。 */
    private static final int HISTORY_LIMIT = 200;
    /** emitter 超时：30 分钟（正常一场面试远小于此，超时即摘除）。 */
    private static final long EMITTER_TIMEOUT_MS = 30 * 60 * 1000L;

    private final ObjectMapper mapper;

    private final Map<Long, CopyOnWriteArrayList<SseEmitter>> emitters = new ConcurrentHashMap<>();
    private final Map<Long, ConcurrentLinkedDeque<AgentEvent>> history = new ConcurrentHashMap<>();
    /** 每个会话一把锁：让「注册订阅者」与「发布事件」互斥，避免重放与实时推送交错产生重复/丢事件。 */
    private final Map<Long, Object> locks = new ConcurrentHashMap<>();

    public SseBroker(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 订阅某会话的事件流：先重放历史事件，再挂上实时推送。
     *
     * @return 已注册好的 emitter，controller 直接返回即可
     */
    public SseEmitter subscribe(Long sessionId) {
        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MS);
        Object lock = lockOf(sessionId);
        List<AgentEvent> replay = new ArrayList<>();
        synchronized (lock) {
            emitters.computeIfAbsent(sessionId, k -> new CopyOnWriteArrayList<>()).add(emitter);
            ConcurrentLinkedDeque<AgentEvent> past = history.get(sessionId);
            if (past != null) {
                replay.addAll(past);
            }
        }

        emitter.onCompletion(() -> detach(sessionId, emitter));
        emitter.onTimeout(() -> detach(sessionId, emitter));
        emitter.onError(t -> detach(sessionId, emitter));

        // 重放放在锁外：避免客户端网络慢时阻塞发布方
        for (AgentEvent e : replay) {
            if (!send(emitter, e)) {
                detach(sessionId, emitter);
                break;
            }
        }
        return emitter;
    }

    /** 发布一批事件：入历史 + 推给所有在线订阅者；含 done 则收流。 */
    public void publish(Long sessionId, List<AgentEvent> events) {
        if (events == null || events.isEmpty()) {
            return;
        }
        Object lock = lockOf(sessionId);
        List<SseEmitter> targets;
        synchronized (lock) {
            ConcurrentLinkedDeque<AgentEvent> dq =
                    history.computeIfAbsent(sessionId, k -> new ConcurrentLinkedDeque<>());
            for (AgentEvent e : events) {
                dq.addLast(e);
                while (dq.size() > HISTORY_LIMIT) {
                    dq.pollFirst();
                }
            }
            targets = new ArrayList<>(emitters.getOrDefault(sessionId, new CopyOnWriteArrayList<>()));
        }

        for (SseEmitter em : targets) {
            for (AgentEvent e : events) {
                if (!send(em, e)) {
                    detach(sessionId, em);
                    break;
                }
            }
        }

        boolean finished = events.stream().anyMatch(e -> AgentEvent.DONE.equals(e.type()));
        if (finished) {
            complete(sessionId);
        }
    }

    /** 结束某会话的所有流（done 或客户端主动断开时）。 */
    public void complete(Long sessionId) {
        CopyOnWriteArrayList<SseEmitter> list;
        synchronized (lockOf(sessionId)) {
            list = emitters.remove(sessionId);
        }
        if (list == null) {
            return;
        }
        for (SseEmitter em : list) {
            try {
                em.complete();
            } catch (IllegalStateException ignored) {
                // 已 complete/超时，忽略
            }
        }
    }

    // ---------- 内部 ----------

    private boolean send(SseEmitter emitter, AgentEvent event) {
        try {
            emitter.send(SseEmitter.event()
                    .name(event.type())
                    .data(mapper.writeValueAsString(event.data())));
            return true;
        } catch (IOException | RuntimeException e) {
            // 客户端断开 / emitter 已超时或已 complete，判为失效由调用方摘除
            return false;
        }
    }

    private void detach(Long sessionId, SseEmitter emitter) {
        emitters.getOrDefault(sessionId, new CopyOnWriteArrayList<>()).remove(emitter);
        try {
            emitter.complete();
        } catch (IllegalStateException ignored) {
            // 已 complete，忽略
        }
    }

    private Object lockOf(Long sessionId) {
        return locks.computeIfAbsent(sessionId, k -> new Object());
    }
}
