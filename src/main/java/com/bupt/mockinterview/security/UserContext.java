package com.bupt.mockinterview.security;

/**
 * 当前请求的用户上下文（R1-1）。
 * <p>
 * 由 {@link JwtAuthFilter} 在请求进入时填充，请求结束后清理（try-finally）。
 * 业务代码通过 {@link #getUserId()} 获取当前用户 ID，所有查询/写入据此隔离数据。
 * <p>
 * ThreadLocal 不对外暴露——只能 get/set/clear，避免被误删或泄漏。
 */
public final class UserContext {

    private static final ThreadLocal<Long> USER_ID = new ThreadLocal<>();
    private static final ThreadLocal<String> USERNAME = new ThreadLocal<>();

    private UserContext() {}

    public static void set(Long userId, String username) {
        USER_ID.set(userId);
        USERNAME.set(username);
    }

    /** 当前用户 ID；未认证时返回 null。 */
    public static Long getUserId() {
        return USER_ID.get();
    }

    public static String getUsername() {
        return USERNAME.get();
    }

    public static void clear() {
        USER_ID.remove();
        USERNAME.remove();
    }
}
