package com.bupt.mockinterview.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.TenantId;

import java.time.Instant;

@Entity
@Table(name = "interview_session", indexes = { @Index(name = "idx_session_user", columnList = "user_id"), @Index(name = "idx_session_user_created", columnList = "user_id, created_at"), @Index(name = "idx_session_status", columnList = "status") })
@Getter
@Setter
@NoArgsConstructor
public class InterviewSession {

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

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "jd_profile_id", nullable = false)
    private JdProfile jdProfile;

    @Column(columnDefinition = "TEXT") // JSON 数组：启用的角色，如 ["技术深挖官"] 或 ["技术深挖官","项目质疑官"]（部分启用；Critic 始终在）
    private String roles;

    @Enumerated(EnumType.STRING)
    @Column(length = 32)
    private SessionStatus status;

    private Integer matchScore; // 本 JD 当前匹配度（Critic 填）

    /**
     * 本场主问题上限（不含追问），开始时由用户选择；null 视为默认
     * {@link com.bupt.mockinterview.agent.InterviewOrchestrator#MAX_MAIN_QUESTIONS}。
     * T10 引入：支持「快速练 3 题 / 完整 8 题」。旧数据为 null，按默认 8 处理。
     */
    private Integer maxQuestions;

    /**
     * 本场关联的简历版本 id（纯 id，无外键约束；开始面试时由用户在下拉中选择）。
     * null=未指定。当前 RAG 检索全局语料，不按简历版本隔离（隔离为后续迭代项），
     * 该列仅留痕，供历史回看/未来按版本过滤使用。
     */
    private Long resumeId;

    @CreationTimestamp
    @Column(updatable = false)
    private Instant createdAt;
}
