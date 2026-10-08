package com.bupt.mockinterview.web;

import com.bupt.mockinterview.domain.User;
import com.bupt.mockinterview.domain.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * app_user（不分区表）行级落库服务（R2-T0）。
 * <p>
 * 所有方法固定 REQUIRES_NEW：在<b>调用方当前 TenantContext</b> 对应的新物理 Session 中
 * 独立提交。注册白名单路径需要先独立提交用户行，随后的赠额事务才能在外键上看到它
 * （同事务未提交的 user 对 REQUIRES_NEW 的 quota 事务不可见）。User 实体不带
 * {@code @TenantId}，故系统租户 0L 下插入 app_user 不落任何分区值。
 */
@Service
public class UserLifecycleService {

    private final UserRepository userRepo;

    public UserLifecycleService(UserRepository userRepo) {
        this.userRepo = userRepo;
    }

    /** 新建并独立提交用户（密码须已 BCrypt），返回带自增 id 的实体。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public User insertUser(String username, String encodedPassword) {
        return userRepo.save(new User(username, encodedPassword));
    }

    /** 独立事务删除用户（赠额失败补偿用）；app_user 不分区，按主键删除。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void deleteUser(User user) {
        userRepo.delete(user);
    }
}
