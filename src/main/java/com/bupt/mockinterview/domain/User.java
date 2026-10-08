package com.bupt.mockinterview.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

/**
 * 用户账号（R1-1， 新增）。
 * <p>
 * 表名 {@code app_user} 避开 H2 / MySQL {@code user} 保留字。
 * 密码用 BCrypt 加密（{@code org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder}），
 * 不存明文也不存加盐哈希——BCrypt 自带盐值。
 */
@Entity
@Table(name = "app_user", indexes = @Index(name = "uk_username", columnList = "username", unique = true))
@Getter
@Setter
@NoArgsConstructor
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 用户名，唯一，4-32 字符。 */
    @Column(unique = true, nullable = false, length = 32)
    private String username;

    /** BCrypt 加密后的密码哈希。 */
    @Column(nullable = false, name = "password_hash", length = 128)
    private String passwordHash;

    /** 对话模型调用方式：平台 key（默认）/ 自带 key（BYOK，R1-3）。 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, name = "key_mode", length = 16)
    private KeyMode keyMode = KeyMode.PLATFORM;

    /** 对话模型厂商（R3-2）：DeepSeek（默认）/ 通义千问。与 keyMode 解耦，设置即时生效。 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, name = "chat_provider", length = 16)
    private ChatProviderChoice chatProvider = ChatProviderChoice.DEEPSEEK;

    /** 角色：公开注册恒 USER（默认）；ADMIN 仅由 BootstrapAdminInitializer 经 env 引导（R2-T0 D3）。 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private UserRole role = UserRole.USER;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    public User(String username, String passwordHash) {
        this.username = username;
        this.passwordHash = passwordHash;
    }
}
