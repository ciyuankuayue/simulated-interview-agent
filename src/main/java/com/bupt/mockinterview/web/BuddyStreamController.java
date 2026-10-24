package com.bupt.mockinterview.web;

import com.bupt.mockinterview.agent.CompanionService;
import com.bupt.mockinterview.agent.StudyCoachService;
import com.bupt.mockinterview.billing.QuotaExhaustedException;
import com.bupt.mockinterview.llm.LlmException;
import com.bupt.mockinterview.security.CurrentUserService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;
import java.util.function.Consumer;

/** Retains authenticated request context; does not open an async Hibernate session. */
@RestController
public class BuddyStreamController {
    private final StudyCoachService coach;
    private final CompanionService companion;
    private final CurrentUserService currentUser;
    private final ObjectMapper mapper;

    public BuddyStreamController(StudyCoachService coach, CompanionService companion,
                                 CurrentUserService currentUser, ObjectMapper mapper) {
        this.coach = coach;
        this.companion = companion;
        this.currentUser = currentUser;
        this.mapper = mapper;
    }

    public record Message(@NotBlank(message = "消息不能为空") @Size(max = 6000, message = "消息最多 6000 字符") String message) {}

    @PostMapping(path = {"/api/coach/stream", "/api/companion/stream"}, produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public void stream(@Valid @RequestBody Message body, HttpServletRequest request, HttpServletResponse response) {
        Long userId = currentUser.currentUserId();
        response.setCharacterEncoding("UTF-8");
        response.setContentType(MediaType.TEXT_EVENT_STREAM_VALUE);
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Accel-Buffering", "no");
        Consumer<String> delta = text -> event(response, "delta", Map.of("text", text));
        try {
            Map<String, Object> result = request.getRequestURI().equals("/api/coach/stream")
                    ? coach.stream(userId, body.message().trim(), delta)
                    : companion.stream(userId, body.message().trim(), delta);
            event(response, "done", result);
        } catch (RuntimeException e) {
            if (!response.isCommitted()) {
                response.resetBuffer();
                response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                throw e;
            }
            if (hasDisconnect(e)) return;
            int status = e instanceof QuotaExhaustedException ? 402 : e instanceof IllegalStateException ? 409
                    : e instanceof LlmException ? 504 : 500;
            String code = status == 402 ? "QUOTA_EXHAUSTED" : status == 409 ? "CONFLICT"
                    : status == 504 ? "LLM_UNAVAILABLE" : "INTERNAL_ERROR";
            try {
                event(response, "error", Map.of("status", status, "code", code,
                        "message", status == 500 ? "回复暂未完成，请稍后重试" : String.valueOf(e.getMessage())));
            } catch (UncheckedIOException ignored) { /* Client left after its last delta. */ }
        }
    }

    private void event(HttpServletResponse response, String name, Object value) {
        try {
            response.getWriter().write("event: " + name + "\ndata: " + mapper.writeValueAsString(value) + "\n\n");
            response.flushBuffer();
            if (response.getWriter().checkError()) throw new IOException("client disconnected");
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }

    private static boolean hasDisconnect(Throwable error) {
        for (Throwable next = error; next != null; next = next.getCause()) if (next instanceof UncheckedIOException) return true;
        return false;
    }
}
