package com.bupt.mockinterview.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.TenantId;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

/**
 * 用户自带 DeepSeek API Key（BYOK，R1-3）。
 * <p>
 * 一用户至多一条（user_id 唯一）。明文<b>永不落库、永不回传</b>：
 * 入库前由 {@code security.UserKeyCipher} 做 AES-256-GCM 加密（KEK 来自 env APP_DATA_KEY），
 * 仅额外保存末 4 位用于界面展示（sk-...abcd）。
 * BYOK 仅支持 DeepSeek；embedding（DashScope）始终走平台 key。
 */
@Entity
@Table(name = "user_api_key",
        indexes = @Index(name = "uk_user_api_key_user", columnList = "user_id", unique = true))
@Getter
@Setter
@NoArgsConstructor
public class UserApiKey {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** R2-T0 多租户分区列：Hibernate @TenantId 自动回填/过滤，业务代码不赋值。 */
    @TenantId
    @Column(name = "tenant_id")
    private Long tenantId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /** AES-256-GCM 密文（Base64，内含 12B IV 与 128b tag）。 */
    @Column(name = "key_ciphertext", nullable = false, length = 2048)
    private String keyCiphertext;

    /** 明文 key 末 4 位（展示用）。 */
    @Column(name = "key_last4", nullable = false, length = 8)
    private String keyLast4;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public UserApiKey(User user, String keyCiphertext, String keyLast4) {
        this.user = user;
        this.keyCiphertext = keyCiphertext;
        this.keyLast4 = keyLast4;
    }
}
