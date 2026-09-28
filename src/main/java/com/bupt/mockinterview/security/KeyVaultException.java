package com.bupt.mockinterview.security;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * 密钥保险箱相关的业务异常。密码错误 → 401（需要用户重新输入，而非 400 参数错误）。
 */
@ResponseStatus(HttpStatus.UNAUTHORIZED)
public class KeyVaultException extends RuntimeException {

    public KeyVaultException(String message) {
        super(message);
    }

    public KeyVaultException(String message, Throwable cause) {
        super(message, cause);
    }
}
