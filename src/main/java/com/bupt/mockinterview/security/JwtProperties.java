package com.bupt.mockinterview.security;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * JWT 配置绑定（R1-1）。前缀 {@code jwt}，见 {@code application.yml}。
 */
@Data
@ConfigurationProperties(prefix = "jwt")
public class JwtProperties {

    /** 签名密钥（至少 32 字节）。空值时 JwtService 启动时自动生成（仅开发便利）。 */
    private String secret = "";

    /** 令牌有效期，毫秒。默认 24 小时。 */
    private long expiryMs = 24L * 60 * 60 * 1000;
}
