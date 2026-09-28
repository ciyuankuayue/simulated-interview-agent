package com.bupt.mockinterview.web;

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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
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
 * T10 三个改进点的契约验收（LLM/RAG 用 stub，不烧 token；类上 @Transactional 回滚不污染共享 H2）：
 * 1. 历史会话列表 hasReport 翻转 + 报告可回看；
 * 2. 错题本详情 lastSessionId/lastTurnSeq 定位答崩现场，end 后补出 Critic 四段讲解；
 * 3. start.maxQuestions：非法值 400；=1 时答完首题（含一层追问）即 done，reason=MAX_QUESTIONS_REACHED。
 * 另：/api/health/deps 必须暴露 embedder 可观测块。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class T10FeaturesTest {

    private static final String TOPIC = "MySQL 覆盖索引";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper mapper;

    @MockBean
    private LlmPort llm;
    @MockBean
    private RagService rag;

    @Test
    void historyList_hasReportFlipsAfterEnd_andReportReachable() throws Exception {
        stubChat();
        when(llm.followUp(anyString(), anyString()))
                .thenReturn(new LlmReply("说说你最熟的一个技术点？", null));
        when(llm.score(anyString(), anyString()))
                .thenReturn(new ScoreResult(8, Map.of(), List.of(), ""));

        long jdId = createJd();
        long sessionId = startSession(jdId, null);

        // end 前：列表有这场，但 hasReport=false（进行中的场次不可点开）
        JsonNode before = findSession(sessionId);
        assertThat(before.get("hasReport").asBoolean()).isFalse();
        assertThat(before.get("status").asText()).isEqualTo("QUESTIONING");
        assertThat(before.has("maxQuestions")).isTrue();

        mvc.perform(post("/api/interview/" + sessionId + "/end"));

        // end 后：hasReport=true，且报告接口可达（历史回看走的就是它）
        assertThat(findSession(sessionId).get("hasReport").asBoolean()).isTrue();
        JsonNode report = mapper.readTree(body(mvc.perform(get("/api/interview/" + sessionId + "/report"))));
        assertThat(report.get("perTurnReview")).isNotEmpty();
    }

    @Test
    void weakPointDetail_locatesLatestScene_thenReviewAfterEnd() throws Exception {
        stubChat();
        when(llm.followUp(anyString(), anyString()))
                .thenReturn(new LlmReply("覆盖索引是什么？", null));
        // 首答 0 分 → 记错题本（带现场）；追问层留着不作答，直接 end
        when(llm.score(anyString(), anyString()))
                .thenReturn(new ScoreResult(0,
                        Map.of("正确性", 0, "完整性", 0, "深度", 0, "实证", 0),
                        List.of("没说覆盖索引免回表"), "那联合索引呢？", TOPIC));

        long jdId = createJd();
        long sessionId = startSession(jdId, null);
        answer(sessionId, "不会");  // 0 分 → 错题本 + 追问 L1；追问层不必作答，直接 end 也能出报告

        JsonNode list = mapper.readTree(body(mvc.perform(get("/api/weak-point"))));
        assertThat(list).hasSize(1);
        long weakId = list.get(0).get("weakPointId").asLong();
        assertThat(list.get(0).get("lastSessionId").asLong()).isEqualTo(sessionId);
        assertThat(list.get(0).get("lastTurnSeq").asInt()).isOne();

        // end 前：现场可定位（题/答/分），但还没有 Critic 讲解
        JsonNode detail = mapper.readTree(body(mvc.perform(get("/api/weak-point/" + weakId))));
        JsonNode scene = detail.get("latest");
        assertThat(scene.get("sessionId").asLong()).isEqualTo(sessionId);
        assertThat(scene.get("turnSeq").asInt()).isOne();
        assertThat(scene.get("question").asText()).contains("覆盖索引");
        assertThat(scene.get("answer").asText()).isEqualTo("不会");
        assertThat(scene.get("score").asInt()).isZero();
        assertThat(scene.get("review").isNull()).isTrue();

        // end 后：同一条详情补出报告里的逐轮讲解
        mvc.perform(post("/api/interview/" + sessionId + "/end"));
        JsonNode after = mapper.readTree(body(mvc.perform(get("/api/weak-point/" + weakId))));
        JsonNode review = after.get("latest").get("review");
        assertThat(review.get("explanation").asText()).isNotBlank();
        assertThat(review.get("exemplarAnswer").asText()).isNotBlank();

        // 不存在的条目 → 404
        int notFound = mvc.perform(get("/api/weak-point/999999"))
                .andReturn().getResponse().getStatus();
        assertThat(notFound).isEqualTo(404);
    }

    @Test
    void maxQuestions_invalidRejected_andOneQuestionEndsSession() throws Exception {
        stubChat();
        when(llm.followUp(anyString(), anyString()))
                .thenReturn(new LlmReply("只问一道：谈谈 GC。", null));
        when(llm.score(anyString(), anyString()))
                .thenReturn(new ScoreResult(0,
                        Map.of("正确性", 0, "完整性", 0, "深度", 0, "实证", 0),
                        List.of("没答到点"), "再追一句？", TOPIC))
                .thenReturn(new ScoreResult(8, Map.of(), List.of(), ""));

        long jdId = createJd();

        // 越界值 → 400（0 和 9 都非法，区间 1-8）
        assertThat(mvc.perform(post("/api/interview/start").contentType(MediaType.APPLICATION_JSON)
                .content("{\"jdId\":" + jdId + ",\"maxQuestions\":0}"))
                .andReturn().getResponse().getStatus()).isEqualTo(400);
        assertThat(mvc.perform(post("/api/interview/start").contentType(MediaType.APPLICATION_JSON)
                .content("{\"jdId\":" + jdId + ",\"maxQuestions\":9}"))
                .andReturn().getResponse().getStatus()).isEqualTo(400);

        long sessionId = startSession(jdId, 1);
        assertThat(findSession(sessionId).get("maxQuestions").asInt()).isOne();

        answer(sessionId, "完全不会");   // 首题主问题 0 分 → 触发追问 L1
        answer(sessionId, "现在会了：GC 分代回收…");  // 追问 8 分 → 不再追问，主问题数已达上限 → 自动出报告 + done

        // 自然结束：SSE 重放必须包含 report（且在 done 之前）+ done + 达上限原因
        String stream = body(mvc.perform(get("/api/interview/" + sessionId + "/events")));
        assertThat(stream).contains("event:report");
        assertThat(stream).contains("event:done").contains("MAX_QUESTIONS_REACHED");
        assertThat(stream.indexOf("event:report")).isLessThan(stream.indexOf("event:done"));

        // 自然结束与手动点「结束」等价：会话 DONE、hasReport 翻转、matchScore 落库、报告接口可达
        JsonNode ended = findSession(sessionId);
        assertThat(ended.get("status").asText()).isEqualTo("DONE");
        assertThat(ended.get("hasReport").asBoolean()).isTrue();
        assertThat(ended.get("matchScore").asInt()).isEqualTo(55);
        JsonNode report = mapper.readTree(body(mvc.perform(get("/api/interview/" + sessionId + "/report"))));
        assertThat(report.get("perTurnReview")).isNotEmpty();
        assertThat(report.get("matchScore").asInt()).isEqualTo(55);

        // 幂等：自然结束后再手动 end，返回同一份报告，不重复调 Critic
        mvc.perform(post("/api/interview/" + sessionId + "/end"));
        JsonNode again = mapper.readTree(body(mvc.perform(get("/api/interview/" + sessionId + "/report"))));
        assertThat(again.get("matchScore").asInt()).isEqualTo(55);
        assertThat(again.get("overallNotes").asText()).isEqualTo("继续练");
    }

    @Test
    void resumeSelection_validAccepted_unknownRejected() throws Exception {
        stubChat();
        when(llm.followUp(anyString(), anyString()))
                .thenReturn(new LlmReply("简历版本选中了吗？", null));
        when(llm.score(anyString(), anyString()))
                .thenReturn(new ScoreResult(8, Map.of(), List.of(), ""));

        long jdId = createJd();

        // 上传一份简历，start 携带其 id → 正常开始
        String resumeResp = body(mvc.perform(post("/api/resume")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"# 张三\\nJava 后端候选人\"}")));
        long resumeId = mapper.readTree(resumeResp).get("resumeId").asLong();

        String okResp = body(mvc.perform(post("/api/interview/start")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"jdId\":" + jdId + ",\"resumeId\":" + resumeId + "}")));
        assertThat(mapper.readTree(okResp).get("sessionId").asLong()).isPositive();

        // 不存在的简历 id → 404
        int notFound = mvc.perform(post("/api/interview/start")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"jdId\":" + jdId + ",\"resumeId\":999999}"))
                .andReturn().getResponse().getStatus();
        assertThat(notFound).isEqualTo(404);

        // 简历列表可下拉复用
        JsonNode list = mapper.readTree(body(mvc.perform(get("/api/resume"))));
        assertThat(list.get(0).get("resumeId").asLong()).isEqualTo(resumeId);
        assertThat(list.get(0).get("isActive").asBoolean()).isTrue();
    }

    @Test
    void depsEndpoint_exposesEmbedderObservability() throws Exception {
        JsonNode deps = mapper.readTree(body(mvc.perform(get("/api/health/deps"))));
        JsonNode embedder = deps.get("embedder");
        assertThat(embedder.get("provider").asText()).isNotBlank();
        assertThat(embedder.get("vectorChunks").isNumber()).isTrue();
        // 测试环境无论 Fake 降级还是真通义（继承到 key），ping 都应成功
        assertThat(embedder.get("ping").get("ok").asBoolean()).isTrue();
        assertThat(embedder.get("ping").get("dim").asInt()).isPositive();
    }

    // ---------- 辅助 ----------

    private long createJd() throws Exception {
        String resp = body(mvc.perform(post("/api/jd")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"rawText\":\"招聘 Java 后端，要求 MySQL、JVM、并发\"}")));
        return mapper.readTree(resp).get(0).get("jdId").asLong();
    }

    private long startSession(long jdId, Integer maxQuestions) throws Exception {
        String body = "{\"jdId\":" + jdId + ",\"roles\":[\"技术深挖官\"]"
                + (maxQuestions == null ? "" : ",\"maxQuestions\":" + maxQuestions) + "}";
        String resp = body(mvc.perform(post("/api/interview/start")
                .contentType(MediaType.APPLICATION_JSON).content(body)));
        return mapper.readTree(resp).get("sessionId").asLong();
    }

    private void answer(long sessionId, String text) throws Exception {
        String resp = body(mvc.perform(post("/api/interview/" + sessionId + "/answer")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"answer\":\"" + text + "\"}")));
        assertThat(mapper.readTree(resp).get("accepted").asBoolean()).isTrue();
    }

    private JsonNode findSession(long sessionId) throws Exception {
        JsonNode arr = mapper.readTree(body(mvc.perform(get("/api/interview/sessions"))));
        for (JsonNode s : arr) {
            if (s.get("sessionId").asLong() == sessionId) {
                return s;
            }
        }
        throw new AssertionError("sessions 列表中找不到 session " + sessionId);
    }

    /** chat 同时承担 JD 解析与 Critic 报告，按 system 提示内容分流（同 InterviewFlowTest）。 */
    private void stubChat() {
        org.mockito.stubbing.Answer<LlmReply> chatAnswer = inv -> {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> messages = inv.getArgument(2);
            String system = String.valueOf(messages.get(0).get("content"));
            if (system.contains("招聘 JD 分析助手")) {
                return new LlmReply("{\"keywords\":[\"MySQL\",\"JVM\"],\"jobType\":\"Java 后端\"}", null);
            }
            return new LlmReply("""
                    {"matchScore":55,
                     "perTurnReview":[{"turnSeq":1,"question":"覆盖索引","score":0,
                        "explanation":"覆盖索引免回表","correction":"概念完全缺失",
                        "knowledgeSupplement":"联合索引最左前缀","exemplarAnswer":"索引列覆盖查询列即可免回表"}],
                     "collapsedPoints":["覆盖索引"],
                     "expressionIssues":[{"issue":"没展开","guide":"结构化"}],
                     "overallNotes":"继续练",
                     "reminders":["复习索引"]}
                    """, null);
        };
        when(llm.chat(any(), anyBoolean(), any(), any(), anyBoolean())).thenAnswer(chatAnswer);
        when(llm.chat(any(), anyBoolean(), any(), any(), anyBoolean(), anyInt())).thenAnswer(chatAnswer);
    }

    private static String body(ResultActions actions) throws Exception {
        return actions.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }
}
