package com.bupt.mockinterview.operations;

import jakarta.persistence.*;

import lombok.*;

import org.hibernate.annotations.TenantId;

@Entity
@Table(
        name = "analytics_preference",
        uniqueConstraints = @UniqueConstraint(name = "uk_analytics_user", columnNames = "user_id"))
@Getter
@Setter
@NoArgsConstructor
public class AnalyticsPreference {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @TenantId
    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "report_invitation_seen", nullable = false)
    private boolean reportInvitationSeen;
}
