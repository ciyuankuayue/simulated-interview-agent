package com.bupt.mockinterview.operations;

import org.springframework.core.annotation.Order;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestControllerAdvice
@Order(-10)
public class OperationExceptionHandler {
    @ExceptionHandler(OperationException.class)
    public ResponseEntity<?> handle(OperationException ex) {
        return ResponseEntity.status(ex.status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("code", ex.code, "message", ex.getMessage()));
    }
}
