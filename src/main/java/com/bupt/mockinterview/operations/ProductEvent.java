package com.bupt.mockinterview.operations;

import jakarta.persistence.*;

import lombok.*;

import org.hibernate.annotations.TenantId;

import java.time.Instant;

@Entity
@Table(
        name = "product_event",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uk_event_user_id",
                        columnNames = {"user_id", "event_id"}),
        indexes = {
            @Index(name = "idx_event_user_created", columnList = "user_id,created_at"),
            @Index(name = "idx_event_created_name", columnList = "created_at,name")
        })
@Getter
@Setter
@NoArgsConstructor
public class ProductEvent {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @TenantId
    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "event_id", nullable = false, length = 120)
    private String eventId;

    @Column(nullable = false, length = 40)
    private String name;

    @Column(length = 24)
    private String page;

    @Column(name = "session_id")
    private Long sessionId;

    @Column(name = "duration_ms")
    private Long durationMs;

    @Column(length = 32)
    private String source;

    @Column(length = 12)
    private String device;

    @Column(name = "result_code", length = 64)
    private String resultCode;

    @Column(name = "server_event", nullable = false)
    private boolean serverEvent;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}
