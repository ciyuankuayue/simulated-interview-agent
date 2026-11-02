package com.bupt.mockinterview.operations;

import jakarta.persistence.*;

import lombok.*;

import org.hibernate.annotations.TenantId;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(
        name = "operation_grant",
        uniqueConstraints =
                @UniqueConstraint(name = "uk_grant_request", columnNames = "request_id"),
        indexes = @Index(name = "idx_grant_user_created", columnList = "user_id,created_at"))
@Getter
@Setter
@NoArgsConstructor
public class OperationGrant {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @TenantId
    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "operator_id", nullable = false)
    private Long operatorId;

    @Column(name = "request_id", nullable = false, length = 100)
    private String requestId;

    @Column(nullable = false, length = 24)
    private String type;

    @Column(nullable = false, length = 500)
    private String reason;

    @Column(name = "grant_tokens", nullable = false)
    private long grantTokens;

    @Column(name = "amount_cny", precision = 12, scale = 2)
    private BigDecimal amountCny;

    @Column(name = "order_ref", length = 120)
    private String orderRef;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}
