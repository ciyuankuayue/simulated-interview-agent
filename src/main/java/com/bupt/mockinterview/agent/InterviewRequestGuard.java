package com.bupt.mockinterview.agent;

import org.springframework.stereotype.Component;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/** Reject overlapping answer/end HTTP requests before any model call or transaction starts. */
@Component
public class InterviewRequestGuard {
    private record Key(Long userId, Long sessionId) {}
    private final Set<Key> active = ConcurrentHashMap.newKeySet();

    public <T> T execute(Long userId, Long sessionId, Supplier<T> operation) {
        Key key = new Key(userId, sessionId);
        if (!active.add(key)) throw new IllegalStateException("本场面试正在处理回答或报告，请等待当前操作完成");
        try { return operation.get(); } finally { active.remove(key); }
    }
}
