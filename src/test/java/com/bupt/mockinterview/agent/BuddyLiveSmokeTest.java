package com.bupt.mockinterview.agent;

import com.bupt.mockinterview.domain.ChatProviderChoice;
import com.bupt.mockinterview.domain.User;
import com.bupt.mockinterview.domain.repository.LlmUsageLogRepository;
import com.bupt.mockinterview.domain.repository.UserRepository;
import com.bupt.mockinterview.memory.BuddyMemoryService;
import com.bupt.mockinterview.security.TenantContext;
import com.bupt.mockinterview.security.UserContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/** Explicit opt-in. Synthetic conversations only; real provider usage and local H2 bookkeeping. */
@EnabledIfSystemProperty(named = "buddy.live", matches = "true")
@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:buddy_live;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "deepseek.api-key=${DEEPSEEK_API_KEY:}", "dashscope.api-key=${DASHSCOPE_API_KEY:}"})
class BuddyLiveSmokeTest {
    @Autowired StudyCoachService coach;
    @Autowired CompanionService companion;
    @Autowired BuddyMemoryService memory;
    @Autowired User testUser;
    @Autowired UserRepository users;
    @Autowired LlmUsageLogRepository usage;
    @Autowired ObjectMapper mapper;

    @Test
    void syntheticTeachingAndSupportRemainContinuousAndReallyStream() throws Exception {
        String provider = System.getProperty("buddy.provider", "DEEPSEEK");
        List<Map<String, Object>> turns = new ArrayList<>();
        Map<String, Object> artifact = new LinkedHashMap<>();
        UserContext.set(testUser.getId(), testUser.getUsername());
        TenantContext.set(testUser.getId());
        try {
            testUser.setChatProvider(ChatProviderChoice.valueOf(provider));
            users.saveAndFlush(testUser);
            memory.setEnabled(testUser.getId(), true);
            String[][] cases = {
                    {"coach", "我的目标岗位是Java后端校招，我喜欢先举例再讲原理。请讲讲volatile有什么用，面试会怎么问？"},
                    {"coach", "我觉得volatile可以保证i++的线程安全，对吗？"},
                    {"coach", "今天不测了，请换个比喻解释为什么不保证i++线程安全。"},
                    {"companion", "准备两个月面试还是没过，朋友都拿到offer了，我觉得自己很差。"},
                    {"companion", "你说的比较不是重点，我最难受的是已经很努力了，结果还是证明不了自己。"},
                    {"companion", "我只想抱怨，不要建议。以后我难过时请先听我说，别急着给建议。"},
                    {"companion", "今天第一次不看笔记也把一个项目讲清楚了，好开心！"}
            };
            for (String[] sample : cases) {
                long started = System.nanoTime();
                long[] first = {0};
                StringBuilder shown = new StringBuilder();
                java.util.function.Consumer<String> delta = text -> {
                    if (first[0] == 0) first[0] = System.nanoTime();
                    shown.append(text);
                };
                Map<String, Object> response = sample[0].equals("coach")
                        ? coach.stream(testUser.getId(), sample[1], delta)
                        : companion.stream(testUser.getId(), sample[1], delta);
                assertThat(response.get("reply").toString()).isNotBlank().doesNotContain("<buddy_memory>");
                assertThat(shown.toString().trim()).isEqualTo(response.get("reply"));
                turns.add(Map.of("mode", sample[0], "input", sample[1], "reply", response.get("reply"),
                        "firstDeltaMs", (first[0] - started) / 1_000_000,
                        "completeMs", (System.nanoTime() - started) / 1_000_000));
            }
            artifact.put("memory", memory.view(testUser.getId()));
            assertThat(memory.view(testUser.getId()).entries()).extracting(BuddyMemoryService.Entry::key)
                    .contains("target_role", "explanation_style", "support_style");
            var records = usage.findByUserIdOrderByIdDesc(testUser.getId(), PageRequest.of(0, 50));
            artifact.put("usage", records.stream().map(r -> Map.of("model", r.getModel(), "tokens", r.getTotalTokens())).toList());
            artifact.put("totalTokens", records.stream().mapToLong(r -> r.getTotalTokens()).sum());
        } finally {
            artifact.put("scope", "Seven synthetic turns; live " + provider + "; not a clinical or overall learning-quality evaluation");
            artifact.put("turns", turns);
            Files.writeString(Path.of("target/buddy-live-" + provider.toLowerCase() + ".json"),
                    mapper.writerWithDefaultPrettyPrinter().writeValueAsString(artifact));
            UserContext.clear();
            TenantContext.clear();
        }
    }
}
