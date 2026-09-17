package com.bupt.mockinterview.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "interview_turn")
@Getter
@Setter
@NoArgsConstructor
public class InterviewTurn {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "session_id", nullable = false)
    private InterviewSession session;

    @Column(columnDefinition = "TEXT")
    private String question;

    @Column(columnDefinition = "TEXT")
    private String answer;

    @Column(columnDefinition = "TEXT") // JSON 数组：知识块 id 列表（检索引用）
    private String retrieveRefs;

    @Column(columnDefinition = "TEXT") // JSON：{score, breakdown, missed_points, followup}（内部，决定是否追问；不向用户全量展示）
    private String scoreJson;

    private Long elapsedMs;

    private Integer tokens;

    private Integer seq; // 轮次序号（追问层数 ≤ 2）
}
