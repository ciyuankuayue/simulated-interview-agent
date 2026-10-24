package com.bupt.mockinterview.web;

import com.bupt.mockinterview.agent.CompanionService;
import com.bupt.mockinterview.security.CurrentUserService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * F10 心理助手 / 情绪陪伴。
 *
 * <p>POST /api/companion/chat —— 发送一句心里话，返回陪伴回应（命中自伤关键词则返回安全支持）。
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

    public record ChatRequest(@NotBlank(message = "消息不能为空") @Size(max = 6000, message = "消息最多 6000 字符") String message) {}

    @PostMapping("/chat")
    public Map<String, Object> chat(@Valid @RequestBody ChatRequest req) {
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
