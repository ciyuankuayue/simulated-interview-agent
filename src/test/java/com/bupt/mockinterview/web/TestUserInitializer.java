package com.bupt.mockinterview.web;

import com.bupt.mockinterview.billing.QuotaService;
import com.bupt.mockinterview.domain.User;
import com.bupt.mockinterview.domain.repository.UserRepository;
import com.bupt.mockinterview.security.UserContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 测试专用：启动时创建 dummy user + 设置 UserContext ThreadLocal。
 * <p>
 * 解决 R1-1 改造后所有实体 user_id NOT NULL 的问题——
 * 禁用 JWT 后 JwtAuthFilter 不注册 → UserContext 永远 null →
 * CurrentUserService.currentUser() → null → entity.setUser(null) 触发约束异常。
 * <p>
 * 放在 {@code com.bupt.mockinterview.web} 包下，主应用 @SpringBootApplication 默认
 * scanBasePackages 会扫到（test 源码在 classpath 上），无需手动 @Import。
 * <p>
 * 用 @Bean 而非 @PostConstruct，避免 UserRepository 尚未初始化的时序问题。
 * @Bean 每次 ApplicationContext 启动都会执行；只存 H2 内存库，@Transactional 测试会自动回滚。
 */
@Configuration
public class TestUserInitializer {

    /**
     * 创建并持久化一个测试 User（用户名 testuser，密码 BCrypt("password")），并建额度账户。
     * 返回值同时注册为 Bean，方便测试类直接 @Autowired 使用。
     */
    @Bean
    public User testUser(UserRepository userRepo, PasswordEncoder encoder, QuotaService quotaService) {
        User user = userRepo.findByUsername("testuser").orElseGet(() ->
                userRepo.save(new User("testuser", encoder.encode("password"))));
        // PLATFORM 路径不扣点，但额度账户仍建上（GET /api/user/quota 与隔离测试用）
        quotaService.grantIfAbsent(user);
        return user;
    }

    /**
     * 在 testUser Bean 创建后，把其 userId / username 写入 UserContext ThreadLocal。
     * 这样 CurrentUserService.currentUser() / currentUserId() 都能正常工作。
     */
    @Bean
    public TestUserSetup testUserSetup(User testUser) {
        UserContext.set(testUser.getId(), "testuser");
        return new TestUserSetup(testUser);
    }

    /**
     * 每个 HTTP 请求前把 UserContext 置为 testuser，请求结束后恢复（而不是清空）。
     * <p>
     * 为什么需要它：UserIsolationTest 用 {@code @SpringBootTest(properties="app.auth.jwt-enabled=true")}
     * 局部启用 JwtAuthFilter，其 finally 会 {@code UserContext.clear()} 清掉主线程 ThreadLocal；
     * 所有 @SpringBootTest 测试在同一 main 线程串行跑，clear 之后再无 context 初始化的话，
     * 后续「jwt=false 且 context 已提前创建」的测试会拿到 null 用户。
     * <p>
     * 本 filter 最高优先级 = 最外层：jwt=true context 中业务身份仍由内层 JwtAuthFilter 决定，
     * 但任何请求结束后主线程都恢复成 testuser，顺序不再敏感。直接调 bean（不走 HTTP）的测试
     * 仍靠 {@link #testUserSetup} 的一次性预置。
     */
    @Bean
    public TestUserContextFilter testUserContextFilter(User testUser) {
        // 必须实现 Ordered：@Order 加在 @Bean 方法上对 MockMvc 的 Filter 实例排序不生效，
        // 一旦本 filter 排到 JwtAuthFilter 内层，就会用 testuser 覆盖掉 JWT 解析出的真实用户
        return new TestUserContextFilter(testUser);
    }

    /**
     * 测试身份 filter：最外层包裹，请求前置 testuser、请求后恢复（不清空）。
     * jwt=true context 中业务身份仍由内层 JwtAuthFilter 决定。
     */
    static final class TestUserContextFilter extends OncePerRequestFilter implements Ordered {
        private final User testUser;

        TestUserContextFilter(User testUser) {
            this.testUser = testUser;
        }

        @Override
        public int getOrder() {
            return Ordered.HIGHEST_PRECEDENCE;
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                        FilterChain chain) throws ServletException, IOException {
            UserContext.set(testUser.getId(), "testuser");
            try {
                chain.doFilter(request, response);
            } finally {
                UserContext.set(testUser.getId(), "testuser");
            }
        }
    }

    /** 测试标记 Bean，方便断言 User 是否已初始化（可选）。 */
    public record TestUserSetup(User user) {}
}
