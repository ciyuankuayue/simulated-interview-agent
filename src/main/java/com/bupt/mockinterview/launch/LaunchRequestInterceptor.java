package com.bupt.mockinterview.launch;

import com.bupt.mockinterview.security.UserContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Set;

@Component
public class LaunchRequestInterceptor implements HandlerInterceptor {
    private final LaunchLimits limits;
    private final LaunchAccessLimiter limiter;
    private static final String LEASE = LaunchRequestInterceptor.class.getName();
    private static final Set<String> MATERIALS = Set.of("/api/jd", "/api/resume", "/api/note", "/api/project-doc", "/api/real-interview");
    public LaunchRequestInterceptor(LaunchLimits limits, LaunchAccessLimiter limiter) {
        this.limits = limits; this.limiter = limiter;
    }
    @Override public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!limits.isEnabled() || !Set.of("POST", "PUT", "PATCH").contains(request.getMethod())) return true;
        String path = request.getRequestURI();
        if (request.getContentLengthLong() > limits.getMaxRequestBytes())
            throw new LaunchLimitException(413, "CONTENT_TOO_LARGE", "提交内容过大，请缩短后重试");
        // Do not trust caller-supplied X-Forwarded-For. Reverse-proxy address handling is deployment configuration.
        if (path.equals("/api/auth/register")) limiter.hit("register", request.getRemoteAddr(), limits.getRegistrationsPerHour(), 3600);
        else if (path.equals("/api/auth/login")) limiter.hit("login", request.getRemoteAddr(), limits.getLoginsPerMinute(), 60);
        Long userId = UserContext.getUserId();
        if (userId != null) {
            limiter.hit("write", userId.toString(), limits.getWritesPerMinute(), 60);
            if (modelOperation(path)) {
                request.setAttribute(LEASE, limiter.acquire(userId)); request.setAttribute(LEASE + ".user", userId);
            }
        }
        return true;
    }
    static boolean modelOperation(String path) {
        return MATERIALS.contains(path) || path.startsWith("/api/project-doc/")
                || path.equals("/api/interview/start") || path.matches("/api/interview/[0-9]+/(answer|end)")
                || path.matches("/api/(companion|coach)/(chat|stream)");
    }
    @Override public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        if (request.getAttribute(LEASE) instanceof String token && request.getAttribute(LEASE + ".user") instanceof Long userId)
            limiter.release(userId, token);
    }
}
