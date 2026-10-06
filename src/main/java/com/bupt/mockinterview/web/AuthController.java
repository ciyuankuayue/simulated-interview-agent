package com.bupt.mockinterview.web;

import com.bupt.mockinterview.billing.QuotaService;
import com.bupt.mockinterview.domain.User;
import com.bupt.mockinterview.domain.UserRole;
import com.bupt.mockinterview.domain.repository.UserRepository;
import com.bupt.mockinterview.security.JwtService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
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
    private final QuotaService quotaService;

    public AuthController(UserRepository userRepo, PasswordEncoder passwordEncoder,
                          JwtService jwtService, QuotaService quotaService) {
        this.userRepo = userRepo;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.quotaService = quotaService;
    }

    /** 注册新用户 → 自动签发 JWT。库中首个账号为 ADMIN（全新部署引导注册）；并赠送服务点数。 */
    @PostMapping("/register")
    @Transactional
    public ResponseEntity<?> register(@Valid @RequestBody RegisterRequest req) {
        if (userRepo.existsByUsername(req.username)) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("code", "USERNAME_TAKEN");
            body.put("message", "用户名已被占用");
            return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
        }

        User user = new User(req.username, passwordEncoder.encode(req.password));
        // 全新库的第一个注册者 = 管理员；其余均普通用户
        if (userRepo.count() == 0) {
            user.setRole(UserRole.ADMIN);
        }
        userRepo.save(user);
        // 注册即建额度并赠送点数（同事务，失败整体回滚）
        quotaService.grantForNewUser(user);

        String token = jwtService.generateToken(user.getId(), user.getUsername());
        return ResponseEntity.status(HttpStatus.CREATED).body(responseOf(user, token));
    }

    /** 登录 → 换取 JWT。 */
    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest req) {
        User user = userRepo.findByUsername(req.username).orElse(null);
        if (user == null || !passwordEncoder.matches(req.password, user.getPasswordHash())) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("code", "INVALID_CREDENTIALS");
            body.put("message", "用户名或密码错误");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(body);
        }
        String token = jwtService.generateToken(user.getId(), user.getUsername());
        return ResponseEntity.ok(responseOf(user, token));
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
