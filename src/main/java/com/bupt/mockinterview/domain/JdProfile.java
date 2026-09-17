package com.bupt.mockinterview.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

@Entity
@Table(name = "jd_profile")
@Getter
@Setter
@NoArgsConstructor
public class JdProfile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

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
