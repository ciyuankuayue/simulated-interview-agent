package com.bupt.mockinterview.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

@Entity
@Table(name = "interview_session")
@Getter
@Setter
@NoArgsConstructor
public class InterviewSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "jd_profile_id", nullable = false)
    private JdProfile jdProfile;

    @Column(columnDefinition = "TEXT") // JSON 数组：启用的角色，如 ["技术深挖官"] 或 ["技术深挖官","项目质疑官"]（部分启用；Critic 始终在）
    private String roles;

    @Enumerated(EnumType.STRING)
    @Column(length = 32)
    private SessionStatus status;

    private Integer matchScore; // 本 JD 当前匹配度（Critic 填）

    @CreationTimestamp
    @Column(updatable = false)
    private Instant createdAt;
}
