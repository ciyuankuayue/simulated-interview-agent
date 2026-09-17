package com.bupt.mockinterview.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

@Entity
@Table(name = "project_doc")
@Getter
@Setter
@NoArgsConstructor
public class ProjectDoc {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 255, nullable = false)
    private String title;

    @Column(columnDefinition = "TEXT", nullable = false) // 项目详解 md：背景/目标 → 选型理由 → 关键设计/架构 → 踩坑与解决 → 假设未来某问题的处理
    private String content;

    @UpdateTimestamp
    private Instant updatedAt; // 记录/补充「现状」（质疑官可据此追问演进）
}
