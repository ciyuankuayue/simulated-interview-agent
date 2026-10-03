package com.bupt.mockinterview.security;

/**
 * 已认证但权限不足（R2：非 ADMIN 调管理接口 → 403 FORBIDDEN）。
 * 与 401（未认证/JWT 失效）区分开。
 */
public class ForbiddenException extends RuntimeException {

    public ForbiddenException(String message) {
        super(message);
    }
}
