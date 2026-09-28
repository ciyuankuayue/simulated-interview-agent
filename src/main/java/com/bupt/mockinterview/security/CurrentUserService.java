package com.bupt.mockinterview.security;

import com.bupt.mockinterview.domain.User;
import com.bupt.mockinterview.domain.repository.UserRepository;
import org.springframework.stereotype.Service;

/**
 * R1-1 辅助：把 UserContext（ThreadLocal 里的 userId）解析成完整的 {@link User} 实体。
 * 所有需要多用户隔离的 Controller / Service 注入本 Bean，调用 {@link #currentUser()} 拿当前用户。
 */
@Service
public class CurrentUserService {

    private final UserRepository userRepo;

    public CurrentUserService(UserRepository userRepo) {
        this.userRepo = userRepo;
    }

    /**
     * 当前请求的用户实体（已过 JWT 校验，理论上不会 null；
     * 未认证（JwtAuthFilter 白名单场景）时也可能 null，调用方判空处理）。
     */
    public User currentUser() {
        Long userId = UserContext.getUserId();
        if (userId == null) return null;
        return userRepo.findById(userId).orElse(null);
    }

    /** 当前请求的 userId；未认证时 null。 */
    public Long currentUserId() {
        return UserContext.getUserId();
    }
}
