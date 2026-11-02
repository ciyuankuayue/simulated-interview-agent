package com.bupt.mockinterview.launch;

import com.bupt.mockinterview.security.CurrentUserService;
import com.bupt.mockinterview.web.MaterialController;
import jakarta.persistence.EntityManager;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.HttpHeaders;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdviceAdapter;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

/** Enforce bounded JSON even with chunked encoding, and total owner-scoped source size before model calls. */
@ControllerAdvice
public class LaunchBodyAdvice extends RequestBodyAdviceAdapter {
    private final LaunchLimits limits;
    private final EntityManager em;
    private final CurrentUserService currentUser;
    public LaunchBodyAdvice(LaunchLimits limits, EntityManager em, CurrentUserService currentUser) {
        this.limits = limits; this.em = em; this.currentUser = currentUser;
    }
    @Override public boolean supports(MethodParameter p, Type t, Class<? extends HttpMessageConverter<?>> c) { return limits.isEnabled(); }
    @Override public HttpInputMessage beforeBodyRead(HttpInputMessage input, MethodParameter p, Type t,
            Class<? extends HttpMessageConverter<?>> c) throws IOException {
        InputStream bounded = new FilterInputStream(input.getBody()) {
            private long count;
            private void add(long amount) {
                count += Math.max(0, amount);
                if (count > limits.getMaxRequestBytes())
                    throw new LaunchLimitException(413, "CONTENT_TOO_LARGE", "提交内容过大，请缩短后重试");
            }
            @Override public int read() throws IOException { int n = super.read(); if (n >= 0) add(1); return n; }
            @Override public int read(byte[] b, int off, int len) throws IOException {
                int n = in.read(b, off, len); add(n); return n;
            }
        };
        return new HttpInputMessage() {
            public InputStream getBody() { return bounded; }
            public HttpHeaders getHeaders() { return input.getHeaders(); }
        };
    }
    @Override public Object afterBodyRead(Object body, HttpInputMessage input, MethodParameter parameter, Type type,
            Class<? extends HttpMessageConverter<?>> converter) {
        List<String> texts = new ArrayList<>();
        if (body instanceof MaterialController.JdRequest r) {
            if (r.rawText() != null && !r.rawText().isBlank()) texts.add(r.rawText());
            if (r.rawTexts() != null) r.rawTexts().stream().filter(v -> v != null && !v.isBlank()).forEach(texts::add);
            if (texts.size() > limits.getMaxBatchJds()) tooLarge("一次最多导入 " + limits.getMaxBatchJds() + " 条岗位描述");
        } else if (body instanceof MaterialController.ResumeRequest r) texts.add(r.content());
        else if (body instanceof MaterialController.NoteRequest r) texts.add(r.content());
        else if (body instanceof MaterialController.RealInterviewRequest r) texts.add(r.content());
        else if (body instanceof MaterialController.ProjectDocRequest r) {
            texts.add(r.content()); if (r.title() != null && r.title().length() > 200) tooLarge("标题最多200字符");
        }
        if (texts.isEmpty()) return body;
        for (String text : texts) if (text != null && text.length() > limits.getMaxTextChars())
            tooLarge("单份资料最多 " + limits.getMaxTextChars() + " 字符，请拆分后导入");
        Long userId = currentUser.currentUserId();
        if (userId == null) return body; // historical disabled-auth internal tests only
        long incoming = texts.stream().filter(java.util.Objects::nonNull).mapToLong(String::length).sum();
        long stored = 0;
        for (String entity : List.of("ResumeVersion", "Note", "ProjectDoc", "RealInterviewRecord", "JdProfile")) {
            String field = entity.equals("JdProfile") ? "rawText" : "content";
            stored += em.createQuery("select coalesce(sum(length(m." + field + ")),0) from " + entity
                            + " m where m.user.id=:owner and m.tenantId=:owner", Long.class)
                    .setParameter("owner", userId).getSingleResult();
        }
        // Replacing a project counts only the delta; verify owner before subtracting any existing size.
        if (parameter.getMethod() != null && parameter.getMethod().getName().equals("updateProjectDoc")) {
            var request = ((org.springframework.web.context.request.ServletRequestAttributes)
                    org.springframework.web.context.request.RequestContextHolder.currentRequestAttributes()).getRequest();
            String[] parts = request.getRequestURI().split("/");
            try {
                List<Integer> prior = em.createQuery("select length(m.content) from ProjectDoc m where m.id=:id and m.user.id=:owner and m.tenantId=:owner", Integer.class)
                        .setParameter("id", Long.parseLong(parts[parts.length - 1])).setParameter("owner", userId).getResultList();
                if (!prior.isEmpty()) stored -= prior.get(0);
            } catch (NumberFormatException ignored) { /* controller validates path */ }
        }
        if (stored + incoming > limits.getMaxStoredChars()) tooLarge("资料总量已达到内测限制，请联系开发者协助整理");
        return body;
    }
    private static void tooLarge(String message) { throw new LaunchLimitException(413, "MATERIAL_LIMIT", message); }
}
