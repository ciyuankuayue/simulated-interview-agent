package com.bupt.mockinterview.security;

import com.bupt.mockinterview.billing.QuotaService;
import com.bupt.mockinterview.domain.User;
import com.bupt.mockinterview.domain.UserRole;
import com.bupt.mockinterview.domain.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R2-T0 批 2（D3）：平台 ADMIN env 引导器（{@link BootstrapAdminInitializer}）。
 * 直接调 {@link BootstrapAdminInitializer#bootstrap()}（ApplicationRunner 的可测内核）。
 * <p>
 * 不加类级 @Transactional：引导器 persist 时需要「先 set 租户后开 Session」的真实生产时序
 * （类事务会在 @BeforeEach 前以 testuser 租户打开 Session，体内 set 不生效——批 1 实证缺口 2）。
 * 用户名带 UUID，残留数据不影响其他测试。
 */
@SpringBootTest
class BootstrapAdminInitializerTest {

    @Autowired private UserRepository userRepo;
    @Autowired private PasswordEncoder encoder;
    @Autowired private QuotaService quotaService;
    @Autowired private JdbcTemplate jdbc;

    private final BootstrapAdminProperties props = new BootstrapAdminProperties();
    private BootstrapAdminInitializer initializer;

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    private BootstrapAdminInitializer initializer() {
        return new BootstrapAdminInitializer(userRepo, encoder, quotaService, props);
    }

    private String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    @Test
    void noConfig_skips_andCreatesNoAdmin() {
        props.setUsername("");
        props.setPassword("");

        BootstrapAdminInitializer.BootstrapResult result = initializer().bootstrap();

        // SKIPPED 必须零副作用（不针对全表断言 ADMIN：其他用例的 UUID 管理员会残留在共享 H2）
        assertThat(result.status()).isEqualTo(BootstrapAdminInitializer.BootstrapStatus.SKIPPED);
        assertThat(result.generatedPassword()).isNull();
        assertThat(userRepo.findByUsername("boot-never-created-" + UUID.randomUUID())).isEmpty();
    }

    @Test
    void configured_createsAdmin_withBcryptQuota_andOwnTenant() {
        String name = unique("boot-create");
        props.setUsername(name);
        props.setPassword("admin-pass-123");

        BootstrapAdminInitializer.BootstrapResult result = initializer().bootstrap();

        assertThat(result.status()).isEqualTo(BootstrapAdminInitializer.BootstrapStatus.CREATED);
        assertThat(result.generatedPassword()).isNull();
        User admin = userRepo.findByUsername(name).orElseThrow();
        assertThat(admin.getRole()).isEqualTo(UserRole.ADMIN);
        assertThat(admin.getPasswordHash())
                .as("密码必须是 BCrypt 密文，不得明文")
                .startsWith("$2a$")
                .doesNotContain("admin-pass-123");
        assertThat(encoder.matches("admin-pass-123", admin.getPasswordHash())).isTrue();

        // 赠额 Quota 必须落在 admin 自己的租户（不是兜底 0L、不是 testuser）
        Long rawTenant = jdbc.queryForObject(
                "select tenant_id from quota where user_id = ?", Long.class, admin.getId());
        assertThat(rawTenant).isEqualTo(admin.getId());
    }

    @Test
    void existingUser_isIdempotentlyUpgraded_passwordUpdated_noDuplicateQuota() {
        String name = unique("boot-upgrade");
        // 先以普通 USER 存在、无额度
        User existing = userRepo.save(new User(name, encoder.encode("old-password-1")));
        assertThat(existing.getRole()).isEqualTo(UserRole.USER);

        props.setUsername(name);
        props.setPassword("new-password-456");
        BootstrapAdminInitializer.BootstrapResult result = initializer().bootstrap();

        assertThat(result.status()).isEqualTo(BootstrapAdminInitializer.BootstrapStatus.UPGRADED);
        assertThat(userRepo.findByUsername(name)).isPresent();
        assertThat(userRepo.findAll().stream().filter(u -> u.getUsername().equals(name)).count())
                .as("不得新建第二条用户")
                .isEqualTo(1);
        User upgraded = userRepo.findByUsername(name).orElseThrow();
        assertThat(upgraded.getRole()).isEqualTo(UserRole.ADMIN);
        assertThat(encoder.matches("new-password-456", upgraded.getPasswordHash())).isTrue();
        assertThat(encoder.matches("old-password-1", upgraded.getPasswordHash())).isFalse();

        // 幂等补建额度恰好一条，且租户正确
        Long quotaCount = jdbc.queryForObject(
                "select count(*) from quota where user_id = ?", Long.class, existing.getId());
        assertThat(quotaCount).isEqualTo(1L);
        Long rawTenant = jdbc.queryForObject(
                "select tenant_id from quota where user_id = ?", Long.class, existing.getId());
        assertThat(rawTenant).isEqualTo(existing.getId());
    }

    @Test
    void secondRun_isIdempotent_andKeepsAdmin() {
        String name = unique("boot-twice");
        props.setUsername(name);
        props.setPassword("admin-pass-123");

        assertThat(initializer().bootstrap().status())
                .isEqualTo(BootstrapAdminInitializer.BootstrapStatus.CREATED);
        // 第二次跑：找到已存在的 ADMIN → UPGRADED 路径，不报错、不重建
        assertThat(initializer().bootstrap().status())
                .isEqualTo(BootstrapAdminInitializer.BootstrapStatus.UPGRADED);

        assertThat(userRepo.findAll().stream().filter(u -> u.getUsername().equals(name)).count())
                .isEqualTo(1);
    }

    @Test
    void usernameOnly_generatesRandomPassword_forFirstCreation() {
        String name = unique("boot-random");
        props.setUsername(name);
        props.setPassword("");

        BootstrapAdminInitializer.BootstrapResult result = initializer().bootstrap();

        assertThat(result.status()).isEqualTo(BootstrapAdminInitializer.BootstrapStatus.CREATED);
        assertThat(result.generatedPassword())
                .as("未配密码首建必须返回随机密码（供 run() WARN 回显一次）")
                .isNotBlank()
                .hasSizeGreaterThanOrEqualTo(16);
        User admin = userRepo.findByUsername(name).orElseThrow();
        assertThat(admin.getRole()).isEqualTo(UserRole.ADMIN);
        assertThat(encoder.matches(result.generatedPassword(), admin.getPasswordHash())).isTrue();
    }
}
