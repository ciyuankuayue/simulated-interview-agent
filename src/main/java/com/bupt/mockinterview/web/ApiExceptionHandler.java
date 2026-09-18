package com.bupt.mockinterview.web;

import com.bupt.mockinterview.llm.LlmException;
import org.springframework.http.HttpStatus;
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
 * 400 参数错误 / 404 资源不存在 / 409 状态冲突（如会话已 DONE 再回答）/ 500 内部错误 / 504 LLM 超时。
 * 业务层只需抛语义异常，不必关心 HTTP 细节：
 * - {@link IllegalArgumentException} → 404（找不到资源）
 * - {@link IllegalStateException}    → 409（会话状态冲突）
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> notFound(IllegalArgumentException e) {
        return body(HttpStatus.NOT_FOUND, "NOT_FOUND", e.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> conflict(IllegalStateException e) {
        return body(HttpStatus.CONFLICT, "CONFLICT", e.getMessage());
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
        return ResponseEntity.status(status).body(m);
    }
}
