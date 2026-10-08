package com.bupt.mockinterview.security;

import com.bupt.mockinterview.billing.QuotaService;
import com.bupt.mockinterview.domain.User;
import com.bupt.mockinterview.domain.UserRole;
import com.bupt.mockinterview.domain.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Optional;

/**
 * 平台 ADMIN env 引导器（R2-T0 D3）：ADMIN 的<b>唯一</b>产生方式，不开放任何 HTTP 接口。
 * <p>
 * 启动时幂等执行（{@link ApplicationRunner}）：
 * <ul>
 *   <li>username 未配 → SKIPPED（仅 WARN 指引，不影响启动）；</li>
 *   <li>用户不存在 → 建 ADMIN（BCrypt + 注册赠额，Quota 显式落其本人租户）；
 *       password 未配则生成随机密码，由 {@link #run} WARN 回显<b>一次</b>；</li>
 *   <li>用户已存在（含原 USER）→ 幂等升级为 ADMIN；配了密码则同步更新密码；
 *       不新建第二条用户、不重建额度。</li>
 * </ul>
 */
@Component
public class BootstrapAdminInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BootstrapAdminInitializer.class);

    private final UserRepository userRepo;
    private final PasswordEncoder passwordEncoder;
    private final QuotaService quotaService;
    private final BootstrapAdminProperties props;
    private final SecureRandom random = new SecureRandom();

    public BootstrapAdminInitializer(UserRepository userRepo, PasswordEncoder passwordEncoder,
                                     QuotaService quotaService, BootstrapAdminProperties props) {
        this.userRepo = userRepo;
        this.passwordEncoder = passwordEncoder;
        this.quotaService = quotaService;
        this.props = props;
    }

    public enum BootstrapStatus { SKIPPED, CREATED, UPGRADED }

    /**
     * 引导结果。{@code generatedPassword} 仅在「首建且未配密码」时非空，
     * 供 runner 打一条 WARN 回显；其他场景恒为 null。
     */
    public record BootstrapResult(BootstrapStatus status, String generatedPassword) {
        static BootstrapResult skipped() {
            return new BootstrapResult(BootstrapStatus.SKIPPED, null);
        }
    }

    @Override
    public void run(ApplicationArguments args) {
        BootstrapResult result = bootstrap();
        switch (result.status()) {
            case CREATED -> {
                if (result.generatedPassword() != null) {
                    log.warn("【平台 ADMIN 引导】用户 [{}] 已创建，未配置 BOOTSTRAP_ADMIN_PASSWORD，"
                            + "本次随机密码（只显示这一次，请立即登录修改）：{}",
                            props.getUsername(), result.generatedPassword());
                } else {
                    log.info("【平台 ADMIN 引导】用户 [{}] 已创建并授予 ADMIN", props.getUsername());
                }
            }
            case UPGRADED -> log.info("【平台 ADMIN 引导】已有用户 [{}] 已确认为 ADMIN（幂等）", props.getUsername());
            case SKIPPED -> log.warn("【平台 ADMIN 引导】未配置 app.security.bootstrap-admin.username"
                    + "（env BOOTSTRAP_ADMIN_USERNAME），跳过：当前部署将没有 ADMIN 账号。"
                    + "如需管理员，请配置后重启。");
        }
    }

    /** 可测内核：不依赖 Spring 启动事件，执行一次幂等引导。 */
    public BootstrapResult bootstrap() {
        String username = props.getUsername();
        if (username == null || username.isBlank()) {
            return BootstrapResult.skipped();
        }

        Optional<User> existing = userRepo.findByUsername(username);
        if (existing.isEmpty()) {
            String configured = props.getPassword();
            boolean random = configured == null || configured.isBlank();
            String rawPassword = random ? generateRandomPassword() : configured;

            User admin = new User(username, passwordEncoder.encode(rawPassword));
            admin.setRole(UserRole.ADMIN);
            userRepo.save(admin);
            // 与 register 同理：runner 线程无 JWT 过滤器填充租户，显式 set 后再建赠额
            TenantContext.set(admin.getId());
            try {
                quotaService.grantForNewUser(admin);
            } finally {
                TenantContext.clear();
            }
            return new BootstrapResult(BootstrapStatus.CREATED, random ? rawPassword : null);
        }

        User user = existing.get();
        user.setRole(UserRole.ADMIN);
        String configured = props.getPassword();
        if (configured != null && !configured.isBlank()) {
            user.setPasswordHash(passwordEncoder.encode(configured));
        }
        userRepo.save(user);
        // 历史用户可能缺额度行（旁路数据），幂等补建
        TenantContext.set(user.getId());
        try {
            quotaService.grantIfAbsent(user);
        } finally {
            TenantContext.clear();
        }
        return new BootstrapResult(BootstrapStatus.UPGRADED, null);
    }

    /** 24 字节随机 → Base64URL 约 32 字符（无填充，满足注册密码 ≥6 且足够强）。 */
    private String generateRandomPassword() {
        byte[] bytes = new byte[24];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
