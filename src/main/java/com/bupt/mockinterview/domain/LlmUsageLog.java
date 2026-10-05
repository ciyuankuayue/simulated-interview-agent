package com.bupt.mockinterview.domain;

import com.bupt.mockinterview.billing.UsageScene;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

/**
 * LLM / embedding 调用用量流水（R2）。
 * <p>
 * 每次触发计费的调用成功后落一条：厂商 usage 原值 + 实际扣减 token 当量。
 * BYOK 对话时 totalTokens 是真实烧在用户 key 上的量（仅展示），chargedTokens 才是服务费扣减。
 * 只增不改不删。
 */
@Entity
@Table(name = "llm_usage_log",
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

    /** 实际从 quota 扣减的 token 当量（BYOK 对话=服务费；其余=totalTokens）。 */
    @Column(name = "charged_tokens", nullable = false)
    private long chargedTokens;

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
