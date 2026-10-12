package com.bupt.mockinterview.web;

import com.bupt.mockinterview.agent.CompanionService;
import com.bupt.mockinterview.security.CurrentUserService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * F10 心理助手 / 情绪陪伴。
 *
 * <p>POST /api/companion/chat —— 发送一句心里话，返回温暖回应（命中自伤关键词则返回求助热线）。
 * <p>POST /api/companion/reset —— 清空当前用户的陪伴对话历史。
 */
@RestController
@RequestMapping("/api/companion")
public class CompanionController {

    private final CompanionService companion;
    private final CurrentUserService currentUser;

    public CompanionController(CompanionService companion, CurrentUserService currentUser) {
        this.companion = companion;
        this.currentUser = currentUser;
    }

    public record ChatRequest(String message) {}

    @PostMapping("/chat")
    public Map<String, Object> chat(@RequestBody ChatRequest req) {
        if (req == null || req.message() == null || req.message().isBlank()) {
            throw new IllegalArgumentException("message 不能为空");
        }
        Long userId = currentUser.currentUserId();
        return companion.chat(userId, req.message().trim());
    }

    @PostMapping("/reset")
    public Map<String, Object> reset() {
        companion.reset(currentUser.currentUserId());
        return Map.of("ok", true);
    }
}
