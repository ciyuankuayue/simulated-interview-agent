package com.bupt.mockinterview.launch;

import com.bupt.mockinterview.operations.ProductEventService;
import com.bupt.mockinterview.security.TenantContext;
import com.bupt.mockinterview.security.UserContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Record committed business evidence, not button clicks or response text. All telemetry is best-effort. */
@ControllerAdvice
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "app.launch.telemetry-enabled", havingValue = "true", matchIfMissing = true)
public class LaunchActivityAdvice implements ResponseBodyAdvice<Object> {
    private static final Logger log = LoggerFactory.getLogger(LaunchActivityAdvice.class);
    private static final Pattern SESSION = Pattern.compile("/api/interview/([0-9]+)/(answer|end|report)");
    private final ProductEventService events;
    private final EntityManagerFactory factory;
    public LaunchActivityAdvice(ProductEventService events, EntityManagerFactory factory) { this.events = events; this.factory = factory; }
    @Override public boolean supports(MethodParameter p, Class<? extends HttpMessageConverter<?>> c) { return true; }
    @Override public Object beforeBodyWrite(Object body, MethodParameter p, MediaType type,
            Class<? extends HttpMessageConverter<?>> c, ServerHttpRequest request, ServerHttpResponse response) {
        try { observe(body, request, response); }
        catch (Exception e) { log.warn("launch_activity result=skipped reason={}", e.getClass().getSimpleName()); }
        return body;
    }
    private void observe(Object body, ServerHttpRequest request, ServerHttpResponse response) {
        String path = request.getURI().getPath();
        if (!path.startsWith("/api/") || path.startsWith("/api/admin/") || path.startsWith("/api/product-events")) return;
        int status = response instanceof ServletServerHttpResponse servlet ? servlet.getServletResponse().getStatus() : 200;
        boolean success = status >= 200 && status < 300;
        String verb = request.getMethod().name();
        Long userId = UserContext.getUserId();
        Map<?, ?> map = body instanceof Map<?, ?> m ? m : Map.of();
        if (success && (path.equals("/api/auth/register") || path.equals("/api/auth/login")) && map.get("userId") instanceof Number n) {
            userId = n.longValue();
            record(userId, path.endsWith("register") ? "REGISTERED" : "LOGIN",
                    path.endsWith("register") ? "registered-" + userId : UUID.randomUUID().toString(), null, "settings", "OK");
            return;
        }
        if (userId == null) return;
        if (!success && map.get("code") instanceof String code) {
            String name = status == 402 ? "QUOTA_BLOCKED" : status == 504 ? "MODEL_FAILED" : null;
            if (name != null) record(userId, name, UUID.randomUUID().toString(), sessionId(path), "interview", code);
        }
        if (success && path.equals("/api/interview/start") && map.get("sessionId") instanceof Number n)
            record(userId, "INTERVIEW_STARTED", "started-" + n.longValue(), n.longValue(), "interview", "OK");
        Matcher session = SESSION.matcher(path);
        if (session.matches()) committedInterviewFacts(userId, Long.parseLong(session.group(1)));
        if (success && verb.equals("POST") && path.matches("/api/(jd|resume|note|project-doc|real-interview)"))
            record(userId, "IMPORT_SUCCEEDED", UUID.randomUUID().toString(), null, "materials", "OK");
        if (success && verb.equals("PUT") && path.equals("/api/user/settings"))
            record(userId, "SETTINGS_SAVED", UUID.randomUUID().toString(), null, "settings", "OK");
        if (success && verb.equals("POST") && path.matches("/api/(companion|coach)/chat"))
            record(userId, "BUDDY_SUCCEEDED", UUID.randomUUID().toString(), null, path.contains("coach") ? "coach" : "companion", "OK");
    }
    private void committedInterviewFacts(Long userId, Long sessionId) {
        Long previous = TenantContext.getTenantId();
        TenantContext.set(userId);
        // A fresh EntityManager avoids auth/OSIV sessions resolving a different tenant at creation.
        try (EntityManager em = factory.createEntityManager()) {
            List<Integer> seqs = em.createQuery("select t.seq from InterviewTurn t where t.session.id=:session and t.user.id=:owner and t.tenantId=:owner and t.answer is not null and length(t.answer)>0", Integer.class)
                    .setParameter("session", sessionId).setParameter("owner", userId).getResultList();
            for (Integer seq : seqs) record(userId, "ANSWER_SAVED", "answer-" + sessionId + "-" + seq, sessionId, "interview", "OK");
            List<Long> reports = em.createQuery("select r.id from CriticReport r where r.session.id=:session and r.user.id=:owner and r.tenantId=:owner", Long.class)
                    .setParameter("session", sessionId).setParameter("owner", userId).getResultList();
            for (Long report : reports) record(userId, "REPORT_GENERATED", "report-" + report, sessionId, "history", "OK");
        } finally { TenantContext.set(previous); }
    }
    private void record(Long owner, String name, String id, Long session, String page, String result) {
        events.recordServer(owner, name, id, session, page, result);
    }
    private static Long sessionId(String path) { Matcher m = SESSION.matcher(path); return m.matches() ? Long.valueOf(m.group(1)) : null; }
}
