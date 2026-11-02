package com.bupt.mockinterview.operations;

import org.springframework.core.annotation.Order;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestControllerAdvice(assignableTypes = OperationsController.class)
@Order(-20)
public class OperationsValidationAdvice {
    @ExceptionHandler(org.springframework.dao.DataIntegrityViolationException.class)
    public ResponseEntity<?> duplicate(Exception e) {
        return ResponseEntity.status(409)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("code", "REQUEST_CONFLICT", "message", "请求已处理或请求号重复，请刷新查看结果"));
    }

    @ExceptionHandler({
        IllegalArgumentException.class,
        org.springframework.http.converter.HttpMessageNotReadableException.class,
        org.springframework.web.bind.MethodArgumentNotValidException.class
    })
    public ResponseEntity<?> invalid(Exception e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof com.bupt.mockinterview.launch.LaunchLimitException limit)
                return ResponseEntity.status(limit.status())
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(Map.of("code", limit.code(), "message", limit.getMessage()));
        }
        return ResponseEntity.badRequest()
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("code", "BAD_REQUEST", "message", "请求字段无效，请检查填写内容"));
    }
}
