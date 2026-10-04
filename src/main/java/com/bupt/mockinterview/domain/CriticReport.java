package com.bupt.mockinterview.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

@Entity
@Table(name = "critic_report", indexes = { @Index(name = "idx_report_session", columnList = "session_id"), @Index(name = "idx_report_session_user", columnList = "session_id, user_id") })
@Getter
@Setter
@NoArgsConstructor
public class CriticReport {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "session_id", nullable = false)
    private InterviewSession session;

    @Column(columnDefinition = "TEXT") // JSON 数组：每轮 {question, score, explanation, correction, knowledge_supplement, exemplar_answer}
    private String perTurnReview;

    @Column(columnDefinition = "TEXT") // 答崩点汇总
    private String collapsedPoints;

    @Column(columnDefinition = "TEXT") // 表述问题 + 精简答案指导
    private String expressionIssues;

    @Column(columnDefinition = "TEXT") // 整体注意事项
    private String overallNotes;

    @Column(columnDefinition = "TEXT") // 必要提醒面试者
    private String reminders;

    @Column(columnDefinition = "TEXT") // R7：行为维度汇总 JSON（dimensions/summary/suggestions），纯技术场为 null
    private String behaviorReview;

    private Integer matchScore;

    @CreationTimestamp
    @Column(updatable = false)
    private Instant createdAt;
}
