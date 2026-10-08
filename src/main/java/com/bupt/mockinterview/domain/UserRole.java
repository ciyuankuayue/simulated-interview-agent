package com.bupt.mockinterview.domain;

/**
 * 用户角色（R1-3；R2-T0 批 2 修订引导方式）。
 * 公开注册恒为 {@link #USER}；{@link #ADMIN} 只能由平台 env 引导创建/升级
 * （{@code app.security.bootstrap-admin.*} / {@code BOOTSTRAP_ADMIN_USERNAME/PASSWORD}，
 * 见 {@code security.BootstrapAdminInitializer}），不再有「首注册即 ADMIN」。
 */
public enum UserRole {
    ADMIN,
    USER
}
