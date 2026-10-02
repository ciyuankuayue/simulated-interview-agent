package com.bupt.mockinterview.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

/**
 * 用户服务点额度（R1-3 最小骨架，2026-10-02；R2 扩展 token 计量）。
 * <p>
 * 注册即建：total_points = 赠送点数（app.quota.signup-bonus，默认 1000）。
 * R1-3 计量：PLATFORM 模式不扣点；BYOK 模式每次 DeepSeek 调用前扣 byok-charge-per-call（默认 1）点，
 * 条件更新 {@code used_points < total_points} 保证不超发，余额不足 → 402 QUOTA_EXHAUSTED。
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

    @Column(name = "total_points", nullable = false)
    private long totalPoints;

    @Column(name = "used_points", nullable = false)
    private long usedPoints;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public Quota(User user, long totalPoints) {
        this.user = user;
        this.totalPoints = totalPoints;
        this.usedPoints = 0L;
    }

    /** 剩余点数（不小于 0）。 */
    public long remaining() {
        return Math.max(0L, totalPoints - usedPoints);
    }
}
