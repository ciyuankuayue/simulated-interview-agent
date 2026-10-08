package com.bupt.mockinterview.web;

import com.bupt.mockinterview.domain.User;
import com.bupt.mockinterview.security.TenantContext;
import com.bupt.mockinterview.security.UserContext;
import org.springframework.beans.BeansException;
import org.springframework.core.Ordered;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.TestExecutionListener;

/**
 * R2-T0 测试基建（H1）：给非 strict 测试上下文的每个测试方法确定性地填充 testuser 的
 * 用户/租户上下文，并在方法结束后清理。
 * <p>
 * 必要性：
 * <ul>
 *   <li>非 web 测试直接调 service，没有 JwtAuthFilter 填充 ThreadLocal；其状态原本依赖
 *       前序 MockMvc 测试在共享 main 线程上的残留，类执行顺序一变就红（配额/数据落到
 *       兜底租户 0L，读时按 testuser 过滤 → “额度账户不存在”）。</li>
 *   <li>类级 {@code @Transactional} 的事务在 {@code @BeforeEach} 之前开启，而 Hibernate
 *       分区模式在 Session 开启时只解析一次租户——必须赶在事务之前 set，故用 Listener
 *       （order 3500，在依赖注入之后、TransactionalTestExecutionListener(4000) 之前）。</li>
 * </ul>
 * strict 上下文（{@link com.bupt.mockinterview.security.TenantStrictContextTest}）跳过：
 * 那里要验证的正是「无上下文 fail-closed」。测试自己 set/clear 的上下文在 after 阶段
 * 统一清掉，不跨方法泄漏。
 */
public class TestTenantContextListener implements TestExecutionListener, Ordered {

    @Override
    public void beforeTestMethod(TestContext testContext) {
        Boolean strict = testContext.getApplicationContext().getEnvironment()
                .getProperty("app.tenant.strict", Boolean.class, false);
        if (Boolean.TRUE.equals(strict)) {
            return;
        }
        try {
            User testUser = testContext.getApplicationContext().getBean(User.class);
            UserContext.set(testUser.getId(), testUser.getUsername());
            TenantContext.set(testUser.getId());
        } catch (BeansException | IllegalStateException e) {
            // 无 testUser Bean 的上下文（如纯单测/切片）不处理，维持「空 → 兜底 0L」
        }
    }

    @Override
    public void afterTestMethod(TestContext testContext) {
        TenantContext.clear();
        UserContext.clear();
    }

    @Override
    public int getOrder() {
        return 3500;
    }
}
