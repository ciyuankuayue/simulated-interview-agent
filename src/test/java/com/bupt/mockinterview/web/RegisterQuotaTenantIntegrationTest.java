package com.bupt.mockinterview.web;

import com.bupt.mockinterview.domain.User;
import com.bupt.mockinterview.domain.repository.UserRepository;
import com.bupt.mockinterview.security.TenantContext;
import com.bupt.mockinterview.security.UserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * R2-T0：白名单注册路径的赠额租户归属（真实生产时序，非 mock）。
 * <p>
 * 背景：register 在 JWT 白名单内，请求线程没有过滤器填充租户；Spring Boot 默认
 * open-in-view 的 EntityManager/Session 在 preHandle 就已创建（Hibernate 6 的租户标识
 * 在 Session 创建时经 resolver 解析一次并固定）。若注册赠额复用该 Session，quota 行会
 * 落到兜底租户 0L，用户登录后永远查不到自己的额度。
 * <p>
 * 本测试不标类级 @Transactional（真实提交）。注意测试基建的 TestUserContextFilter 会在
 * 每个请求最外层强制填 testuser 租户（比生产白名单的「空上下文」更强）——这恰好也验证了
 * controller 必须在入口显式 set 系统租户、并以 REQUIRES_NEW 新 Session 落赠额：无论请求
 * 入口被谁填成什么，quota 行最终都必须属于新用户本人。再用旁路 JdbcTemplate 绕过租户
 * 过滤断言 quota.tenant_id = 新用户 userId。
 */
@SpringBootTest
@AutoConfigureMockMvc
class RegisterQuotaTenantIntegrationTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private UserRepository userRepo;
    @Autowired
    private JdbcTemplate jdbc;

    @AfterEach
    void clearContexts() {
        TenantContext.clear();
        UserContext.clear();
    }

    @Test
    void register_signupQuotaLandsOnNewUserTenant_notFallbackZero() throws Exception {
        String name = "reg-tenant-" + UUID.randomUUID().toString().substring(0, 8);

        // 精确复现生产白名单：TestTenantContextListener 预填的 testuser 上下文必须清掉，
        // 让 OSIV Session 在「无租户」状态下创建（非 strict 解析兜底 0L）。
        TenantContext.clear();
        UserContext.clear();

        mvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + name + "\",\"password\":\"secret123\"}"))
                .andExpect(status().isCreated());

        User created = userRepo.findByUsername(name).orElseThrow();
        Long rawTenantId = jdbc.queryForObject(
                "SELECT tenant_id FROM quota WHERE user_id = ?", Long.class, created.getId());

        assertThat(rawTenantId)
                .as("注册赠额必须落在新用户本人租户（tenant_id=userId），不能落兜底租户 0L")
                .isEqualTo(created.getId());
        assertThat(rawTenantId).isNotEqualTo(0L);
    }
}
