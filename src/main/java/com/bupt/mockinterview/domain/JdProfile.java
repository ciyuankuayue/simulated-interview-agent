package com.bupt.mockinterview.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.TenantId;

import java.time.Instant;

@Entity
@Table(name = "jd_profile", indexes = { @Index(name = "idx_jd_user", columnList = "user_id"), @Index(name = "idx_jd_user_created", columnList = "user_id, created_at") })
@Getter
@Setter
@NoArgsConstructor
public class JdProfile {

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

    @Column(columnDefinition = "TEXT", nullable = false)
    private String rawText;

    @Column(columnDefinition = "TEXT") // JSON 数组：考点关键词
    private String keywords;

    @Column(length = 64)
    private String jobType;

    @Column(length = 128)
    private String sameTypeTag;

    @CreationTimestamp
    @Column(updatable = false)
    private Instant createdAt;
}
