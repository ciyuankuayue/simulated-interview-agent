package com.bupt.mockinterview.web;

import com.bupt.mockinterview.memory.BuddyMemoryService;
import com.bupt.mockinterview.memory.BuddyMemoryValidationException;
import com.bupt.mockinterview.security.CurrentUserService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** Preferences only; owner identifiers and raw conversation fields are not accepted. */
@RestController
@RequestMapping("/api/buddy/memory")
public class BuddyMemoryController {
    private final BuddyMemoryService memory;
    private final CurrentUserService currentUser;

    public BuddyMemoryController(BuddyMemoryService memory, CurrentUserService currentUser) {
        this.memory = memory;
        this.currentUser = currentUser;
    }

    @GetMapping
    public BuddyMemoryService.View get() {
        return memory.view(userId());
    }

    @PatchMapping
    public BuddyMemoryService.View settings(@RequestBody JsonNode body) {
        requireOnly(body, "enabled");
        if (!body.get("enabled").isBoolean()) throw new BuddyMemoryValidationException("enabled 须为布尔值");
        return memory.setEnabled(userId(), body.get("enabled").booleanValue());
    }

    @PatchMapping("/entries/{key}")
    public BuddyMemoryService.View edit(@PathVariable String key, @RequestBody JsonNode body) {
        requireOnly(body, "value");
        if (!body.get("value").isTextual()) throw new BuddyMemoryValidationException("value 须为字符串");
        return memory.edit(userId(), key, body.get("value").textValue());
    }

    @DeleteMapping("/entries/{key}")
    public BuddyMemoryService.View delete(@PathVariable String key) {
        return memory.delete(userId(), key);
    }

    @DeleteMapping("/entries")
    public BuddyMemoryService.View clear() {
        return memory.clear(userId());
    }

    @PostMapping("/entries/{key}/allow")
    public BuddyMemoryService.View allowAutomatic(@PathVariable String key) {
        return memory.allowAutomatic(userId(), key);
    }

    @ExceptionHandler(BuddyMemoryValidationException.class)
    public ResponseEntity<Map<String, String>> invalid(BuddyMemoryValidationException e) {
        return ResponseEntity.badRequest().body(Map.of("code", "BAD_REQUEST", "message", e.getMessage()));
    }

    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, String>> unreadable() {
        return ResponseEntity.badRequest().body(Map.of("code", "BAD_REQUEST", "message", "请求须为有效的 JSON 对象"));
    }

    @ExceptionHandler(Unauthenticated.class)
    public ResponseEntity<Map<String, String>> unauthorized() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("code", "UNAUTHORIZED", "message", "请先登录"));
    }

    private long userId() {
        Long id = currentUser.currentUserId();
        if (id == null || id <= 0) throw new Unauthenticated();
        return id;
    }

    private static void requireOnly(JsonNode body, String key) {
        if (body == null || !body.isObject() || body.size() != 1 || !body.hasNonNull(key)) {
            throw new BuddyMemoryValidationException("请求只能包含 " + key + " 字段");
        }
    }

    private static final class Unauthenticated extends RuntimeException {}
}
