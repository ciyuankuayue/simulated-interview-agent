package com.bupt.mockinterview.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

/**
 * 用户 token 额度（R2，；替代 R1-3 的 points 模型）。
 * <p>
 * 注册即建：totalTokens = 注册赠送（app.quota.signup-bonus-tokens，默认 500000）。
 * 计量：PLATFORM 对话/embedding 调用后按厂商 usage.total_tokens 实扣；BYOK 对话调用前
 * 扣固定服务费（token 当量）。条件更新保证 used 不超 total；PLATFORM 最后一次可能透支，
 * 由 clamp 更新归零到 total，之后预检拦死。
 */
@Entity
@Table(name = "quota",
        indexes = @Index(name = "uk_quota_user", columnList = "user_id", unique = true))
@Getter
@Setter
@NoArgsConstructor
public class Quota {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "total_tokens", nullable = false)
    private long totalTokens;

    @Column(name = "used_tokens", nullable = false)
    private long usedTokens;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public Quota(User user, long totalTokens) {
        this.user = user;
        this.totalTokens = totalTokens;
        this.usedTokens = 0L;
    }

    /** 剩余 token（不小于 0）。 */
    public long remaining() {
        return Math.max(0L, totalTokens - usedTokens);
    }
}
