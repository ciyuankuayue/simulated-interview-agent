package com.bupt.mockinterview.security;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * R1-1 安全配置。
 * <p>
 * 只注册 BCryptPasswordEncoder（密码加密），不启用完整的 Spring Security Web。
 * 鉴权走手写的 {@link JwtAuthFilter}（R1-3 起为唯一门禁；旧 VaultAccessFilter 已删除）。
 */
@Configuration
@EnableConfigurationProperties({JwtProperties.class, BootstrapAdminProperties.class})
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(10);
    }
}
