package com.bupt.mockinterview.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.hibernate.annotations.TenantId;

/** One bounded preference document per owner; raw conversations are never stored here. */
@Entity
@Table(name = "buddy_memory", indexes = @Index(name = "uk_buddy_memory_user", columnList = "user_id", unique = true))
@Getter
@Setter
@NoArgsConstructor
public class BuddyMemory {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @TenantId
    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, foreignKey = @ForeignKey(name = "fk_buddy_memory_user"))
    @OnDelete(action = OnDeleteAction.CASCADE)
    private User user;

    @Column(nullable = false)
    private boolean enabled;

    /** Explicit settings revision, also checked before saving a model's late result. */
    @Column(nullable = false)
    private long revision;

    @Lob
    @Column(name = "state_json", nullable = false, columnDefinition = "LONGTEXT")
    private String stateJson = "{\"entries\":{},\"blocked\":[]}";

    public BuddyMemory(User user) {
        this.user = user;
    }
}
