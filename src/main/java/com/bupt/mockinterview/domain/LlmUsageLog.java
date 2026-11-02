package com.bupt.mockinterview.domain;

import com.bupt.mockinterview.billing.UsageScene;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.TenantId;

import java.time.Instant;

/**
 * LLM / embedding 调用用量流水（R2）。
 * <p>
 * 每次触发计费的调用成功后落一条：厂商 usage 原值 + 实际扣减 token 当量。
 * BYOK totalTokens为用户Key的真实用量，dueTokens为按比例应扣，chargedTokens为受余额限制后的实扣。
 * 只增不改不删。
 */
@Entity
@Table(name = "llm_usage_log", uniqueConstraints = @UniqueConstraint(name = "uk_usage_user_call", columnNames = {"user_id", "call_id"}),
        indexes = {
                @Index(name = "idx_usage_user", columnList = "user_id"),
                @Index(name = "idx_usage_user_created", columnList = "user_id,created_at")
        })
@Getter
@Setter
@NoArgsConstructor
public class LlmUsageLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** R2-T0 多租户分区列：Hibernate @TenantId 自动回填/过滤，业务代码不赋值。 */
    @TenantId
    @Column(name = "tenant_id")
    private Long tenantId;

    /** 归属用户（不用外键约束对象关联，扣减流水允许轻量写入）。 */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "scene", nullable = false, length = 16)
    private UsageScene scene;

    @Enumerated(EnumType.STRING)
    @Column(name = "key_mode", nullable = false, length = 8)
    private KeyMode keyMode;

    @Column(name = "model", nullable = false, length = 48)
    private String model;

    @Column(name = "prompt_tokens", nullable = false)
    private long promptTokens;

    @Column(name = "completion_tokens", nullable = false)
    private long completionTokens;

    @Column(name = "total_tokens", nullable = false)
    private long totalTokens;

    /** 实际从quota扣减的token当量；不得用应扣或真实用量代替余额不足时的实扣。 */
    @Column(name = "charged_tokens", nullable = false)
    private long chargedTokens;

    @Column(name = "due_tokens")
    private Long dueTokens;
    @Column(name = "call_id", length = 64)
    private String callId;
    @Column(name = "session_id")
    private Long sessionId;
    @Column(name = "billing_version", length = 40)
    private String billingVersion;
    @Column(name = "platform_cost_micros")
    private Long platformCostMicros;
    @Column(name = "pricing_version", length = 40)
    private String pricingVersion;
    @Column(name = "status", length = 20)
    private String status;
    @Column(name = "attempt_count")
    private Integer attemptCount;

    /** true=厂商未回 usage，totalTokens 为字符估算兜底值。 */
    @Column(name = "estimated", nullable = false)
    private boolean estimated;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public LlmUsageLog(Long userId, UsageScene scene, KeyMode keyMode, String model,
                       long promptTokens, long completionTokens, long totalTokens,
                       long chargedTokens, boolean estimated) {
        this.userId = userId;
        this.scene = scene;
        this.keyMode = keyMode;
        this.model = model;
        this.promptTokens = promptTokens;
        this.completionTokens = completionTokens;
        this.totalTokens = totalTokens;
        this.chargedTokens = chargedTokens;
        this.estimated = estimated;
    }
}
