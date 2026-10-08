package com.bupt.mockinterview.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.TenantId;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

@Entity
@Table(name = "project_doc", indexes = @Index(name = "idx_doc_user", columnList = "user_id"))
@Getter
@Setter
@NoArgsConstructor
public class ProjectDoc {

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

    @Column(length = 255, nullable = false)
    private String title;

    @Column(columnDefinition = "TEXT", nullable = false) // 项目详解 md：背景/目标 → 选型理由 → 关键设计/架构 → 踩坑与解决 → 假设未来某问题的处理
    private String content;

    @UpdateTimestamp
    private Instant updatedAt; // 记录/补充「现状」（质疑官可据此追问演进）
}
