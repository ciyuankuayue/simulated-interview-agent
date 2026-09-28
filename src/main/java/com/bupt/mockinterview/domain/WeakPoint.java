package com.bupt.mockinterview.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "weak_point")
@Getter
@Setter
@NoArgsConstructor
public class WeakPoint {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String knowledgePoint;

    private Integer wrongCount = 0;

    private Integer reviewStage = 0; // 0/1/2/3 固定阶梯（方案 A，间隔 [1,3,7,15] 天）

    private Instant nextReviewAt; // 下次复习时间（间隔重复）

    @Column(columnDefinition = "TEXT")
    private String status; // 现状（可关联 project_doc 的「现状」）

    private Instant createdAt; // 首次记入错题本的时间

    /** 是否已出队（domain.md §5：该知识点本轮 score>=6 即标记 resolved，不再进入复习队列）。 */
    private Boolean resolved = false;

    private Instant resolvedAt;

    @ManyToOne(fetch = FetchType.LAZY, optional = true)
    @JoinColumn(name = "jd_profile_id", nullable = true)
    private JdProfile jdProfile;

    /**
     * 最近一次答崩现场（T10 引入，纯 id 不建外键约束——只做「点开回看」的定位）。
     * 详情接口据此聚合 interview_turn 的原题/原答/判分 + 该场报告的逐轮讲解。
     * 旧数据 / 无法定位时为 null，前端详情降级为只显示状。
     */
    private Long lastSessionId;

    private Integer lastTurnSeq;
}
