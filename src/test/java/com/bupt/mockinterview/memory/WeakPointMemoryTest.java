package com.bupt.mockinterview.memory;

import com.bupt.mockinterview.domain.JdProfile;
import com.bupt.mockinterview.domain.WeakPoint;
import com.bupt.mockinterview.domain.repository.JdProfileRepository;
import com.bupt.mockinterview.memory.WeakPointMemoryService.Outcome;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T8 验收：错题本写入 + 间隔重复阶梯（domain.md §5 方案 A 固定阶梯 [1,3,7,15] 天）。
 *
 * 断言的是**规则**而不是「没报错」：
 * 1. 答崩（≤3）新建，stage=0 / 1 天后复习；
 * 2. 再崩升阶：stage 0→1→2→3 且封顶，间隔 1→3→7→15 天，wrong_count 累加；
 * 3. 答对（≥6）出队（resolved），不再进复习队列；
 * 4. 4-5 分两边都不碰（不记也不销）；
 * 5. 措辞漂移（「索引失效场景」vs「索引失效的场景」）要认成同一个知识点；
 * 6. 队列按 next_review_at 升序，due 只返回已到期的。
 *
 * ⚠️ 类上 @Transactional：写库在测试结束时回滚，不污染其他测试类共享的 H2 库
 * （Spring Boot 测试复用同一 context，mem 库是同一个）。
 */
@SpringBootTest
@Transactional
class WeakPointMemoryTest {

    private static final String TOPIC = "MySQL 索引失效场景";

    @Autowired
    private WeakPointMemoryService memory;
    @Autowired
    private JdProfileRepository jdRepo;

    private JdProfile newJd() {
        JdProfile jd = new JdProfile();
        jd.setRawText("招聘 Java 后端：MySQL 索引与优化、Redis、并发编程。");
        return jdRepo.save(jd);
    }

    @Test
    void missOnce_createsWeakPoint_stage0_nextIn1Day() {
        Instant now = Instant.parse("2026-09-18T10:00:00Z");
        Outcome out = memory.onTurnScored(newJd(), TOPIC, 2, "没提覆盖索引", now);

        assertThat(out.created()).isEqualTo(1);
        assertThat(out.escalated()).isZero();
        assertThat(out.resolved()).isZero();

        List<WeakPoint> queue = memory.queue();
        assertThat(queue).hasSize(1);
        WeakPoint w = queue.get(0);
        assertThat(w.getKnowledgePoint()).isEqualTo(TOPIC);
        assertThat(w.getWrongCount()).isEqualTo(1);
        assertThat(w.getReviewStage()).isZero();
        assertThat(w.getStatus()).isEqualTo("没提覆盖索引");
        assertThat(Duration.between(now, w.getNextReviewAt()).toDays()).isEqualTo(1);
        assertThat(w.getResolved()).isFalse();
        assertThat(w.getCreatedAt()).isEqualTo(now);
    }

    @Test
    void missAgain_escalatesStage_andPushesNextReviewAway() {
        JdProfile jd = newJd();
        Instant t0 = Instant.parse("2026-09-18T10:00:00Z");

        memory.onTurnScored(jd, TOPIC, 3, "第一次崩", t0);
        Outcome second = memory.onTurnScored(jd, TOPIC, 2, "第二次崩", t0);
        assertThat(second.escalated()).isEqualTo(1);

        WeakPoint w = memory.queue().get(0);
        assertThat(w.getWrongCount()).isEqualTo(2);
        assertThat(w.getReviewStage()).isEqualTo(1);
        assertThat(Duration.between(t0, w.getNextReviewAt()).toDays()).isEqualTo(3);

        // 再崩两次：stage 2 → 7 天、stage 3 → 15 天
        memory.onTurnScored(jd, TOPIC, 1, "第三次崩", t0);
        w = memory.queue().get(0);
        assertThat(w.getReviewStage()).isEqualTo(2);
        assertThat(Duration.between(t0, w.getNextReviewAt()).toDays()).isEqualTo(7);

        memory.onTurnScored(jd, TOPIC, 0, "第四次崩", t0);
        w = memory.queue().get(0);
        assertThat(w.getReviewStage()).isEqualTo(3);
        assertThat(Duration.between(t0, w.getNextReviewAt()).toDays()).isEqualTo(15);
        assertThat(w.getWrongCount()).isEqualTo(4);

        // 第五次：stage 封顶 3，间隔不再拉长，只加 wrong_count
        memory.onTurnScored(jd, TOPIC, 1, "第五次崩", t0);
        w = memory.queue().get(0);
        assertThat(w.getReviewStage()).isEqualTo(3);
        assertThat(Duration.between(t0, w.getNextReviewAt()).toDays()).isEqualTo(15);
        assertThat(w.getWrongCount()).isEqualTo(5);
        // 升阶是「更新同一条」，不是每次新建
        assertThat(memory.queue()).hasSize(1);
    }

    @Test
    void mastered_resolvesAndLeavesQueue() {
        JdProfile jd = newJd();
        Instant t0 = Instant.parse("2026-09-18T10:00:00Z");

        memory.onTurnScored(jd, TOPIC, 2, "崩了", t0);
        assertThat(memory.queue()).hasSize(1);

        Outcome mastered = memory.onTurnScored(jd, TOPIC, 7, "", t0.plus(Duration.ofDays(1)));
        assertThat(mastered.resolved()).isEqualTo(1);
        assertThat(memory.queue()).isEmpty();

        // 出队后再次答崩 → 重新入队（stage 从 0 起算，wrong_count 重新计数）
        Outcome again = memory.onTurnScored(jd, TOPIC, 1, "又崩了", t0.plus(Duration.ofDays(30)));
        assertThat(again.created()).isEqualTo(1);
        assertThat(memory.queue()).hasSize(1);
    }

    @Test
    void midScore_doesNothing() {
        JdProfile jd = newJd();
        Instant t0 = Instant.parse("2026-09-18T10:00:00Z");

        Outcome five = memory.onTurnScored(jd, TOPIC, 5, "差一点", t0);
        assertThat(five.isEmpty()).isTrue();
        assertThat(memory.queue()).isEmpty();

        // 已在错题本里的知识点拿 4 分也不销（不够「答对」的门槛 6）
        memory.onTurnScored(jd, TOPIC, 2, "崩了", t0);
        Outcome four = memory.onTurnScored(jd, TOPIC, 4, "", t0);
        assertThat(four.isEmpty()).isTrue();
        assertThat(memory.queue()).hasSize(1);
    }

    @Test
    void knowledgePointWordingDrift_stillMatches() {
        JdProfile jd = newJd();
        Instant t0 = Instant.parse("2026-09-18T10:00:00Z");

        memory.onTurnScored(jd, "MySQL 索引失效场景", 2, "崩1", t0);
        // 同一知识点，模型这次多写了个「的」、少写了前缀
        Outcome out = memory.onTurnScored(jd, "索引失效的场景", 1, "崩2", t0);

        assertThat(out.escalated()).isEqualTo(1);
        assertThat(memory.queue()).hasSize(1);
        assertThat(memory.queue().get(0).getWrongCount()).isEqualTo(2);
    }

    @Test
    void differentTopics_staySeparate_andQueueSortedByNextReviewAt() {
        JdProfile jd = newJd();
        Instant t0 = Instant.parse("2026-09-18T10:00:00Z");

        memory.onTurnScored(jd, "MySQL 索引失效场景", 2, "崩", t0);
        memory.onTurnScored(jd, "Redis 缓存穿透", 3, "崩", t0);
        // 让第一条升到 stage 1（+3 天），第二条留在 stage 0（+1 天）
        memory.onTurnScored(jd, "MySQL 索引失效场景", 1, "又崩", t0);

        List<WeakPoint> queue = memory.queue();
        assertThat(queue).hasSize(2);
        assertThat(queue.get(0).getKnowledgePoint()).isEqualTo("Redis 缓存穿透");
        assertThat(Duration.between(t0, queue.get(0).getNextReviewAt()).toDays()).isEqualTo(1);
        assertThat(Duration.between(t0, queue.get(1).getNextReviewAt()).toDays()).isEqualTo(3);

        // due：只返回已到期的
        assertThat(memory.due(t0)).isEmpty();
        assertThat(memory.due(t0.plus(Duration.ofDays(2)))).hasSize(1);
        assertThat(memory.due(t0.plus(Duration.ofDays(10)))).hasSize(2);
    }

    @Test
    void blankKnowledgePoint_isIgnored() {
        Outcome out = memory.onTurnScored(newJd(), "   ", 0, "崩", Instant.now());
        assertThat(out.isEmpty()).isTrue();
        assertThat(memory.queue()).isEmpty();
    }
}
