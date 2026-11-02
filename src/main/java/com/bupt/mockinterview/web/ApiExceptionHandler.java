package com.bupt.mockinterview.web;

import com.bupt.mockinterview.billing.QuotaExhaustedException;
import com.bupt.mockinterview.llm.LlmException;
import com.bupt.mockinterview.security.ForbiddenException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 统一错误响应，对齐 api.md §1：HTTP 状态码 + `{code, message}`。
 *
 * 状态码约定（api.md §1）：
 * 400 参数错误 / 401 JWT 失效 / 402 BYOK 点数耗尽 / 404 资源不存在 /
 * 409 状态冲突（如会话已 DONE 再回答）/ 500 内部错误 / 504 LLM 超时。
 * 业务层只需抛语义异常，不必关心 HTTP 细节：
 * - {@link IllegalArgumentException} → 404（找不到资源）
 * - {@link IllegalStateException}    → 409（会话状态冲突 / BYOK 未配 key）
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> unreadable(org.springframework.http.converter.HttpMessageNotReadableException e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof com.bupt.mockinterview.launch.LaunchLimitException limit) return launchLimit(limit);
        }
        return body(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "提交内容格式不正确");
    }

    @ExceptionHandler(com.bupt.mockinterview.billing.PlatformBudgetExceededException.class)
    public ResponseEntity<Map<String, Object>> platformBudget(com.bupt.mockinterview.billing.PlatformBudgetExceededException e) {
        return body(HttpStatus.CONFLICT, "PLATFORM_BUDGET_REACHED", e.getMessage());
    }

    @ExceptionHandler(com.bupt.mockinterview.launch.LaunchLimitException.class)
    public ResponseEntity<Map<String, Object>> launchLimit(com.bupt.mockinterview.launch.LaunchLimitException e) {
        return body(HttpStatus.valueOf(e.status()), e.code(), e.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> notFound(IllegalArgumentException e) {
        return body(HttpStatus.NOT_FOUND, "NOT_FOUND", e.getMessage());
    }

    /** 按业务键找不到用户（R2 ADMIN 充值）→ 404 USER_NOT_FOUND（比 NOT_FOUND 更精确）。 */
    @ExceptionHandler(UserNotFoundException.class)
    public ResponseEntity<Map<String, Object>> userNotFound(UserNotFoundException e) {
        return body(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", e.getMessage());
    }

    /** 已登录但权限不足（R2：非 ADMIN 调管理接口）→ 403 FORBIDDEN。 */
    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<Map<String, Object>> forbidden(ForbiddenException e) {
        return body(HttpStatus.FORBIDDEN, "FORBIDDEN", e.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> conflict(IllegalStateException e) {
        return body(HttpStatus.CONFLICT, "CONFLICT", e.getMessage());
    }

    /** An overlapping instance must fail before model calls instead of queueing a stale answer. */
    @ExceptionHandler(org.springframework.dao.PessimisticLockingFailureException.class)
    public ResponseEntity<Map<String, Object>> concurrentWrite(org.springframework.dao.PessimisticLockingFailureException e) {
        return body(HttpStatus.CONFLICT, "CONFLICT", "本场面试正在处理，请等待当前操作完成");
    }

    @ExceptionHandler({
            MethodArgumentNotValidException.class,
            MissingServletRequestParameterException.class,
            MissingRequestHeaderException.class,
            MethodArgumentTypeMismatchException.class
    })
    public ResponseEntity<Map<String, Object>> badRequest(Exception e) {
        return body(HttpStatus.BAD_REQUEST, "BAD_REQUEST", e.getMessage());
    }

    /** BYOK 服务点数耗尽 → 402（R1-3；扣点发生在 LLM 调用之前，不会打出半截请求）。 */
    @ExceptionHandler(QuotaExhaustedException.class)
    public ResponseEntity<Map<String, Object>> paymentRequired(QuotaExhaustedException e) {
        return body(HttpStatus.PAYMENT_REQUIRED, "QUOTA_EXHAUSTED", e.getMessage());
    }

    /** LLM 不可用/超时：api.md §1 约定 504（正常链路已有降级，走到这里说明降级也没兜住）。 */
    @ExceptionHandler(LlmException.class)
    public ResponseEntity<Map<String, Object>> llmFailed(LlmException e) {
        return body(HttpStatus.GATEWAY_TIMEOUT, "LLM_UNAVAILABLE", e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> serverError(Exception e) {
        return body(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
    }

    private static ResponseEntity<Map<String, Object>> body(HttpStatus status, String code, String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", code);
        m.put("message", message);
        // SSE clients still receive the documented JSON error before the first response fragment.
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(m);
    }
}
