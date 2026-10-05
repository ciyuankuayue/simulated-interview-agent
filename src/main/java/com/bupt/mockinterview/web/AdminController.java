package com.bupt.mockinterview.web;

import com.bupt.mockinterview.billing.QuotaService;
import com.bupt.mockinterview.domain.Quota;
import com.bupt.mockinterview.domain.User;
import com.bupt.mockinterview.domain.UserRole;
import com.bupt.mockinterview.domain.repository.UserRepository;
import com.bupt.mockinterview.security.CurrentUserService;
import com.bupt.mockinterview.security.ForbiddenException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 管理接口（R2，；api.md §7）。
 * 首版只有 token 充值：ADMIN 按用户名给任意用户加总额度。鉴权仍只有 JWT 一道，
 * 角色校验在方法内显式做（非 ADMIN → 403 FORBIDDEN）。
 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final CurrentUserService currentUser;
    private final UserRepository userRepo;
    private final QuotaService quotaService;

    public AdminController(CurrentUserService currentUser, UserRepository userRepo,
                           QuotaService quotaService) {
        this.currentUser = currentUser;
        this.userRepo = userRepo;
        this.quotaService = quotaService;
    }

    @PostMapping("/quota")
    public Map<String, Object> grantQuota(@Valid @RequestBody GrantRequest req) {
        User operator = currentUser.currentUser();
        if (operator == null || operator.getRole() != UserRole.ADMIN) {
            throw new ForbiddenException("仅管理员可充值 token 额度");
        }
        User target = userRepo.findByUsername(req.username())
                .orElseThrow(() -> new UserNotFoundException("用户不存在：" + req.username()));
        Quota q = quotaService.grantTokens(target.getId(), req.grantTokens());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("totalTokens", q.getTotalTokens());
        body.put("usedTokens", q.getUsedTokens());
        body.put("remainingTokens", q.remaining());
        return body;
    }

    /** 充值请求：grantTokens 必须 &gt; 0（校验失败 → 400 BAD_REQUEST）。 */
    public record GrantRequest(
            @NotBlank String username,
            @NotNull @Min(1) Long grantTokens) {
    }
}
