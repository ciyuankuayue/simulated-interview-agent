package com.bupt.mockinterview.operations;

import jakarta.persistence.*;

import lombok.*;

import org.hibernate.annotations.TenantId;

import java.time.Instant;

@Entity
@Table(
        name = "product_feedback",
        uniqueConstraints = @UniqueConstraint(name = "uk_feedback_user", columnNames = "user_id"))
@Getter
@Setter
@NoArgsConstructor
public class ProductFeedback {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @TenantId
    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false, length = 32)
    private String progress;

    @Column(nullable = false, length = 2000)
    private String experience;

    @Column(nullable = false, length = 250)
    private String blockers;

    @Column(length = 2000)
    private String suggestions;

    @Column(name = "follow_up", nullable = false)
    private boolean followUp;

    @Column(length = 200)
    private String contact;

    @Column(name = "contact_updated_at")
    private Instant contactUpdatedAt;

    @Column(name = "original_experience", length = 2000)
    private String originalExperience;

    @Column(nullable = false, length = 20)
    private String status = "PENDING";

    @Column(name = "review_note", length = 500)
    private String reviewNote;

    @Column(name = "reviewer_id")
    private Long reviewerId;

    @Column(name = "reward_grant_id")
    private Long rewardGrantId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();
}
