package com.bupt.mockinterview.web;

import com.bupt.mockinterview.agent.StudyCoachService;
import com.bupt.mockinterview.security.CurrentUserService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 通考小师独立入口，身份只能从 JWT 派生，不接受客户端指定 userId。 */
@RestController
@RequestMapping("/api/coach")
public class StudyCoachController {
    private final StudyCoachService coach;
    private final CurrentUserService currentUser;

    public StudyCoachController(StudyCoachService coach, CurrentUserService currentUser) {
        this.coach = coach;
        this.currentUser = currentUser;
    }

    public record ChatRequest(
            @NotBlank(message = "问题不能为空")
            @Size(max = 6000, message = "问题最多 6000 字符") String message) {}

    @PostMapping("/chat")
    public Map<String, Object> chat(@Valid @RequestBody ChatRequest request) {
        return coach.chat(currentUser.currentUserId(), request.message().trim());
    }

    @PostMapping("/reset")
    public Map<String, Object> reset() {
        coach.reset(currentUser.currentUserId());
        return Map.of("ok", true);
    }
}
