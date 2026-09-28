package com.bupt.mockinterview.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

/**
 * 用户账号（R1-1，2026-09-28 新增）。
 * <p>
 * 表名 {@code app_user} 避开 H2 / MySQL {@code user} 保留字。
 * 密码用 BCrypt 加密（{@code org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder}），
 * 不存明文也不存加盐哈希——BCrypt 自带盐值。
 */
@Entity
@Table(name = "app_user")
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

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    public User(String username, String passwordHash) {
        this.username = username;
        this.passwordHash = passwordHash;
    }
}
