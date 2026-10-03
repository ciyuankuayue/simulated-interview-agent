package com.bupt.mockinterview.web;

/**
 * 按用户名等业务键找不到用户（R2 ADMIN 充值接口）→ 404 USER_NOT_FOUND。
 * 独立于通用 {@link IllegalArgumentException}（404 NOT_FOUND），前端可精确提示「用户不存在」。
 */
public class UserNotFoundException extends IllegalArgumentException {

    public UserNotFoundException(String message) {
        super(message);
    }
}
