package com.bupt.mockinterview.launch;

import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Redis-backed in prod; bounded single-process development fallback, not a distributed guarantee. */
@Service
public class LaunchAccessLimiter {
    private static final DefaultRedisScript<Long> HIT = new DefaultRedisScript<>(
            "local n=redis.call('INCR',KEYS[1]); if n==1 then redis.call('EXPIRE',KEYS[1],ARGV[1]) end; return n", Long.class);
    private static final DefaultRedisScript<Long> RELEASE = new DefaultRedisScript<>(
            "if redis.call('GET',KEYS[1])==ARGV[1] then return redis.call('DEL',KEYS[1]) end; return 0", Long.class);
    private record Window(long expires, int count) {}
    private record Lease(long expires, String token) {}
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Lease> leases = new ConcurrentHashMap<>();
    private final StringRedisTemplate redis;
    private final boolean distributed;
    public LaunchAccessLimiter(StringRedisTemplate redis, Environment environment) {
        this.redis = redis; this.distributed = environment.acceptsProfiles(Profiles.of("prod"));
    }
    public void hit(String category, String identity, int limit, int seconds) {
        String key = "launch:rate:" + category + ":" + digest(identity);
        long count;
        if (distributed) {
            try { Long result = redis.execute(HIT, List.of(key), Integer.toString(seconds));
                if (result == null) throw new IllegalStateException(); count = result;
            } catch (Exception e) { throw unavailable(); }
        } else {
            prune(); long now = System.currentTimeMillis();
            count = windows.compute(key, (k, v) -> v == null || v.expires <= now
                    ? new Window(now + seconds * 1000L, 1) : new Window(v.expires, v.count + 1)).count;
        }
        if (count > limit) throw new LaunchLimitException(429, "RATE_LIMITED", "操作较频繁，请稍后重试");
    }
    public String acquire(Long userId) {
        String key = "launch:active:" + userId, token = UUID.randomUUID().toString();
        boolean acquired;
        if (distributed) {
            try { acquired = Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key, token, Duration.ofMinutes(10))); }
            catch (Exception e) { throw unavailable(); }
        } else {
            prune(); long now = System.currentTimeMillis();
            Lease value = leases.compute(key, (k, v) -> v == null || v.expires <= now
                    ? new Lease(now + 600_000, token) : v);
            acquired = value.token.equals(token);
        }
        if (!acquired) throw new LaunchLimitException(409, "REQUEST_IN_PROGRESS", "当前账号有操作正在处理，请等待完成");
        return token;
    }
    public void release(Long userId, String token) {
        String key = "launch:active:" + userId;
        if (distributed) {
            try { redis.execute(RELEASE, List.of(key), token); } catch (Exception ignored) { /* bounded TTL */ }
        } else { leases.computeIfPresent(key, (k, v) -> v.token.equals(token) ? null : v); }
    }
    private void prune() {
        if (windows.size() + leases.size() < 2000) return;
        long now = System.currentTimeMillis();
        windows.entrySet().removeIf(e -> e.getValue().expires <= now);
        leases.entrySet().removeIf(e -> e.getValue().expires <= now);
        if (windows.size() + leases.size() > 10000) throw unavailable();
    }
    private static LaunchLimitException unavailable() {
        return new LaunchLimitException(503, "LIMIT_GUARD_UNAVAILABLE", "服务保护暂不可用，请稍后重试");
    }
    private static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
}
