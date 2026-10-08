package com.bupt.mockinterview.web;

import com.bupt.mockinterview.billing.QuotaService;
import com.bupt.mockinterview.domain.User;
import com.bupt.mockinterview.domain.repository.UserRepository;
import com.bupt.mockinterview.security.CurrentTenantResolver;
import com.bupt.mockinterview.security.JwtService;
import com.bupt.mockinterview.security.TenantContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 认证接口（R1-1）。
 * <p>
 * register / login 返回 JWT；logout 保留接口（无状态，客户端扔掉 token 即可）。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final UserRepository userRepo;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final RegistrationService registrationService;

    public AuthController(UserRepository userRepo, PasswordEncoder passwordEncoder,
                          JwtService jwtService, RegistrationService registrationService) {
        this.userRepo = userRepo;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.registrationService = registrationService;
    }

    /**
     * 注册新用户（恒 USER）→ 自动签发 JWT；并赠送服务点数。ADMIN 仅由 env 引导器产生。
     * <p>
     * 白名单无身份路径的租户/事务三段式（系统租户开壳 → 用户行独立提交 → 赠额以本人租户
     * 新 Session 落库）收口在 {@link RegistrationService}，docs/domain.md §9 有完整规约。
     * controller 只负责：进入前置系统租户、异常→HTTP 映射、签发 token、finally 清上下文。
     */
    @PostMapping("/register")
    public ResponseEntity<?> register(@Valid @RequestBody RegisterRequest req) {
        TenantContext.set(CurrentTenantResolver.FALLBACK_TENANT_ID);
        try {
            User user;
            try {
                user = registrationService.registerNewUser(req.username, req.password);
            } catch (RegistrationService.UsernameTakenException e) {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("code", "USERNAME_TAKEN");
                body.put("message", "用户名已被占用");
                return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
            }

            String token = jwtService.generateToken(user.getId(), user.getUsername());
            return ResponseEntity.status(HttpStatus.CREATED).body(responseOf(user, token));
        } finally {
            TenantContext.clear();
        }
    }

    /**
     * 登录 → 换取 JWT。白名单无身份路径：同样先 set 系统租户 0L，否则 strict 下 Session
     * 创建即 fail-closed。只查不分区的 app_user，不碰任何分区表。
     */
    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest req) {
        TenantContext.set(CurrentTenantResolver.FALLBACK_TENANT_ID);
        try {
            User user = userRepo.findByUsername(req.username).orElse(null);
            if (user == null || !passwordEncoder.matches(req.password, user.getPasswordHash())) {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("code", "INVALID_CREDENTIALS");
                body.put("message", "用户名或密码错误");
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(body);
            }
            String token = jwtService.generateToken(user.getId(), user.getUsername());
            return ResponseEntity.ok(responseOf(user, token));
        } finally {
            TenantContext.clear();
        }
    }

    /** 登出（无状态，客户端扔掉 token 即可；保留接口供前端调用）。 */
    @PostMapping("/logout")
    public Map<String, Object> logout() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("loggedOut", true);
        return body;
    }

    private Map<String, Object> responseOf(User user, String token) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("userId", user.getId());
        body.put("username", user.getUsername());
        body.put("token", token);
        return body;
    }

    // ---- 请求 DTO ----

    public record RegisterRequest(
            @NotBlank @Size(min = 4, max = 32) String username,
            @NotBlank @Size(min = 6, max = 128) String password) {}

    public record LoginRequest(
            @NotBlank String username,
            @NotBlank String password) {}
}
