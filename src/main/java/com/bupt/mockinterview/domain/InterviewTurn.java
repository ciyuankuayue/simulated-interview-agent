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

    private Integer seq; // 轮次序号（每条问题/追问各占一个 seq，按答题顺序递增）

    /**
     * 追问层：0=本轮主问题，1/2=对该主问题的第 1/2 层追问（domain.md §4 封顶 2 层）。
     * T6 引入——api.md §4 的 {@code followup} 事件要带 layer，且「何时换下一道主问题」
     * 依赖它，seq 单独表达不了层级。
     */
    private Integer layer;
}
