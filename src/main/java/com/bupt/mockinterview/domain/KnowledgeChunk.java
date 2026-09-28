package com.bupt.mockinterview.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

@Entity
@Table(name = "knowledge_chunk")
@Getter
@Setter
@NoArgsConstructor
public class KnowledgeChunk {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(length = 32) // resume / note / project_doc / real_record
    private String sourceType;

    private Long sourceId; // 来源实体 id

    @Column(columnDefinition = "TEXT")
    private String chunkText;

    @Column(columnDefinition = "TEXT") // JSON 数组：向量（通义 qwen3.7-text-embedding-flash）；MVP 先内存手写余弦，向量可存此处或 Redis
    private String embedding;

    @CreationTimestamp
    @Column(updatable = false)
    private Instant createdAt;
}
