package com.bupt.mockinterview.web;

import com.bupt.mockinterview.billing.QuotaService;
import com.bupt.mockinterview.domain.User;
import com.bupt.mockinterview.domain.repository.UserRepository;
import com.bupt.mockinterview.security.CurrentTenantResolver;
import com.bupt.mockinterview.security.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 公开注册编排（R2-T0 D3 + 多租户事务规约，docs/domain.md §9「白名单无身份路径」）。
 * <p>
 * 背景（实测锁定的 Hibernate 6.5.3 行为）：分区租户在物理 Session 创建时解析一次并固定；
 * Spring 环境下 REQUIRES_NEW <b>仅在当前存在活动事务时</b>才会挂起旧 Session 并新建，
 * 无活动事务时直接复用 OSIV 已绑定的 Session。故注册必须是三层结构：
 * <ol>
 *   <li>{@link #registerNewUser} 本身是壳事务：调用方（AuthController）须在进入前
 *       {@code TenantContext.set(0L)}（系统租户），壳 Session 固定在 0L，只做查重；</li>
 *   <li>{@link UserLifecycleService#insertUser} REQUIRES_NEW：在 0L 新 Session 插入
 *       不分区的 app_user 并<b>独立提交</b>（否则赠额事务外键看不到未提交用户）；</li>
 *   <li>{@code set(新用户id)} 后 {@link QuotaService#grantForNewUser} REQUIRES_NEW：
 *       挂起壳事务 → 新物理 Session 以本人租户解析 → quota 行落本人租户；</li>
 *   <li>赠额失败以独立事务补偿删除用户，避免孤儿账号。</li>
 * </ol>
 */
@Service
public class RegistrationService {

    private static final Logger log = LoggerFactory.getLogger(RegistrationService.class);

    private final UserRepository userRepo;
    private final UserLifecycleService userLifecycle;
    private final org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;
    private final QuotaService quotaService;

    public RegistrationService(UserRepository userRepo, UserLifecycleService userLifecycle,
                                org.springframework.security.crypto.password.PasswordEncoder passwordEncoder,
                                QuotaService quotaService) {
        this.userRepo = userRepo;
        this.userLifecycle = userLifecycle;
        this.passwordEncoder = passwordEncoder;
        this.quotaService = quotaService;
    }

    /** 用户名已占用：controller 映射为 409 USERNAME_TAKEN。 */
    public static class UsernameTakenException extends RuntimeException {
        UsernameTakenException() {
            super("用户名已被占用");
        }
    }

    /**
     * 注册新用户（恒 USER）+ 注册赠额。调用前置：TenantContext 已置系统租户 0L。
     *
     * @return 已持久化（含自增 id）的新用户
     * @throws UsernameTakenException 用户名已存在（壳事务只读回滚，无副作用）
     */
    @Transactional
    public User registerNewUser(String username, String rawPassword) {
        if (userRepo.existsByUsername(username)) {
            throw new UsernameTakenException();
        }

        // 用户行独立提交（0L 系统租户 Session；app_user 不分区）
        User user = userLifecycle.insertUser(username, passwordEncoder.encode(rawPassword));

        // 赠额在本人租户的新物理 Session 中落库（挂起壳事务）
        TenantContext.set(user.getId());
        try {
            quotaService.grantForNewUser(user);
        } catch (RuntimeException e) {
            try {
                userLifecycle.deleteUser(user);
            } catch (RuntimeException deleteError) {
                log.error("注册赠额失败后补偿删除用户也失败，可能残留无额度账号 username={}",
                        username, deleteError);
            }
            throw e;
        } finally {
            // 壳事务由代理在方法返回后提交；恢复与其 Session 一致的系统租户口径
            // （commit 不再解析租户，这里只为防止提交后同线程残留新用户上下文）。
            TenantContext.set(CurrentTenantResolver.FALLBACK_TENANT_ID);
        }
        return user;
    }
}
