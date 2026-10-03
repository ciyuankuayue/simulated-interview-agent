package com.bupt.mockinterview.memory;

import com.bupt.mockinterview.llm.LlmPort;
import com.bupt.mockinterview.llm.LlmReply;
import com.bupt.mockinterview.llm.ScoreResult;
import com.bupt.mockinterview.rag.RagService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * T8 端到端验收：**真跑一场面试**（LLM/RAG 用 stub），答崩后错题本要有记录。
 *
 * 断言的是用户能感知的契约：
 * 1. 低分（2 分）作答 → GET /api/weak-point 出现该知识点，字段齐全；
 * 2. next_review_at = 作答时间 + 1 天（固定阶梯 stage 0）；
 * 3. /api/weak-point/due 按「现在」过滤：当天不到期、两天后到期。
 *
 * ⚠️ 类上 @Transactional：H2 mem 库在测试类间共享，回滚避免污染
 * {@code InterviewFlowTest} 的「错题本为空」断言。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class WeakPointApiTest {

    private static final String TOPIC = "MySQL 索引失效场景";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper mapper;

    @MockBean
    private LlmPort llm;
    @MockBean
    private RagService rag;

    @Test
    void collapsedAnswer_writesWeakPoint_andDueQueryFiltersByTime() throws Exception {
        when(llm.followUp(anyString(), anyString()))
                .thenReturn(new LlmReply("MySQL 索引失效有哪些典型场景？", null));
        // 2 分（0-8）→ 记错题本，且带知识点标签
        when(llm.score(anyString(), anyString()))
                .thenReturn(new ScoreResult(2,
                        Map.of("正确性", 1, "完整性", 0, "深度", 1, "实证", 0),
                        List.of("没提覆盖索引", "没提最左前缀"),
                        "那覆盖索引是怎么避免回表的？",
                        TOPIC));
        when(llm.chat(any(), anyBoolean(), any(), any(), anyBoolean()))
                .thenReturn(new LlmReply("{\"keywords\":[\"MySQL\",\"Redis\"],\"jobType\":\"Java 后端\"}", null));
        // JD 解析走带 scene 的 7 参重载
        when(llm.chat(any(), anyBoolean(), any(), any(), anyBoolean(), anyInt(), any()))
                .thenReturn(new LlmReply("{\"keywords\":[\"MySQL\",\"Redis\"],\"jobType\":\"Java 后端\"}", null));

        long jdId = mapper.readTree(body(mvc.perform(post("/api/jd")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rawText\":\"招聘 Java 后端，要求 MySQL 索引、Redis\"}"))))
                .get(0).get("jdId").asLong();
        long sessionId = mapper.readTree(body(mvc.perform(post("/api/interview/start")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jdId\":" + jdId + ",\"roles\":[\"技术深挖官\"]}"))))
                .get("sessionId").asLong();

        Instant before = Instant.now().minusSeconds(5);
        assertThat(mapper.readTree(body(mvc.perform(post("/api/interview/" + sessionId + "/answer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"answer\":\"用了函数就不会走索引\"}"))))
                .get("accepted").asBoolean()).isTrue();

        // 1) 错题本有记录
        JsonNode weak = mapper.readTree(body(mvc.perform(get("/api/weak-point"))));
        assertThat(weak).hasSize(1);
        JsonNode w = weak.get(0);
        assertThat(w.get("knowledgePoint").asText()).isEqualTo(TOPIC);
        assertThat(w.get("wrongCount").asInt()).isEqualTo(1);
        assertThat(w.get("reviewStage").asInt()).isZero();
        assertThat(w.get("status").asText()).contains("没提覆盖索引");

        // 2) 固定阶梯 stage 0 → 1 天后复习
        Instant next = Instant.parse(w.get("nextReviewAt").asText());
        assertThat(next).isAfter(before.plus(Duration.ofHours(23)));
        assertThat(next).isBefore(Instant.now().plus(Duration.ofHours(25)));

        // 3) due：当天还没到期，两天后就该复习了
        JsonNode dueNow = mapper.readTree(body(mvc.perform(get("/api/weak-point/due"))));
        assertThat(dueNow).isEmpty();

        JsonNode dueLater = mapper.readTree(body(mvc.perform(get("/api/weak-point/due")
                .param("now", Instant.now().plus(Duration.ofDays(2)).toString()))));
        assertThat(dueLater).hasSize(1);
        assertThat(dueLater.get(0).get("knowledgePoint").asText()).isEqualTo(TOPIC);
    }

    private static String body(org.springframework.test.web.servlet.ResultActions actions) throws Exception {
        return actions.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }
}
