package com.bupt.mockinterview.web;

import com.bupt.mockinterview.domain.User;
import com.bupt.mockinterview.domain.repository.UserRepository;
import com.bupt.mockinterview.security.UserContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;

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
     * 创建并持久化一个测试 User（用户名 testuser，密码 BCrypt("password")）。
     * 返回值同时注册为 Bean，方便测试类直接 @Autowired 使用。
     */
    @Bean
    public User testUser(UserRepository userRepo, PasswordEncoder encoder) {
        return userRepo.findByUsername("testuser").orElseGet(() ->
                userRepo.save(new User("testuser", encoder.encode("password"))));
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

    /** 测试标记 Bean，方便断言 User 是否已初始化（可选）。 */
    public record TestUserSetup(User user) {}
}
