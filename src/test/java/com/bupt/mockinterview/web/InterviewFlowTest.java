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
 * T7 验收：web 层 + SSE 全链路（不烧 token——LLM 与 RAG 都用 stub）。
 *
 * 断言的不是「接口没报错」，而是 api.md 契约的几条硬要求：
 * 1. start 同步返回首题；
 * 2. **SSE 能重放到订阅前已产生的事件**（否则前端永远收不到首题）；
 * 3. answer 只回 accepted，且低分触发追问（turnCount 变 2）；
 * 4. end 出报告、status 转 DONE、report 可再拉、重放含 report+done；
 * 5. 已 DONE 再作答 → 409（api.md §1 状态冲突）。
 *
 * ⚠️ 读响应必须显式指定 UTF-8：MockHttpServletResponse 默认按 ISO-8859-1 解码，
 * 中文会变乱码（真实容器无此问题，JSON 默认 UTF-8）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class InterviewFlowTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper mapper;

    /** 用 stub 替掉真实 LLM：本测试验证的是协议与编排，不是模型质量。 */
    @MockBean
    private LlmPort llm;
    /** RAG 也 stub 掉：避免测试依赖 DASHSCOPE_API_KEY 与网络。 */
    @MockBean
    private RagService rag;

    @Test
    void fullFlow_start_answer_events_end_report() throws Exception {
        stubLlm();

        // 1) 建 JD
        String jdResp = body(mvc.perform(post("/api/jd")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"rawText\":\"招聘 Java 后端，要求 MySQL 索引、Redis 缓存、并发编程\"}")));
        long jdId = mapper.readTree(jdResp).get(0).get("jdId").asLong();
        assertThat(jdId).isPositive();

        // 2) start：同步拿首题
        String startResp = body(mvc.perform(post("/api/interview/start")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"jdId\":" + jdId + ",\"roles\":[\"技术深挖官\"]}")));
        JsonNode start = mapper.readTree(startResp);
        long sessionId = start.get("sessionId").asLong();
        assertThat(start.get("firstQuestion").asText()).isNotBlank();
        assertThat(start.get("status").asText()).isEqualTo("QUESTIONING");

        // 3) SSE：连上后应能收到订阅前已产生的 question + await_answer（重放生效）
        String stream = body(mvc.perform(get("/api/interview/" + sessionId + "/events")));
        assertThat(stream)
                .contains("event:question")
                .contains("event:await_answer");

        // 4) answer：只回 accepted
        String answerResp = body(mvc.perform(post("/api/interview/" + sessionId + "/answer")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"answer\":\"二级索引叶子节点存主键，回表要再查聚簇索引\"}")));
        assertThat(mapper.readTree(answerResp).get("accepted").asBoolean()).isTrue();

        // 5) end：出报告
        String endResp = body(mvc.perform(post("/api/interview/" + sessionId + "/end")));
        JsonNode report = mapper.readTree(endResp);
        assertThat(report.get("reportId").asLong()).isPositive();
        assertThat(report.get("matchScore").asInt()).isEqualTo(42);
        assertThat(report.get("perTurnReview")).hasSize(1);
        assertThat(report.get("reminders")).hasSize(1);

        // 6) status：DONE + turnCount
        String statusResp = body(mvc.perform(get("/api/interview/" + sessionId + "/status")));
        JsonNode status = mapper.readTree(statusResp);
        assertThat(status.get("status").asText()).isEqualTo("DONE");
        // 2 = 首题 + 追问（stub 判 4 分，followupDepth=2、已追问 0 层 → 必触发一层追问）
        assertThat(status.get("turnCount").asInt()).isEqualTo(2);
        assertThat(status.get("roles").get(0).asText()).isEqualTo("技术深挖官");
        assertThat(status.get("matchScore").asInt()).isEqualTo(42);

        // 7) report 可重复拉取
        String againResp = body(mvc.perform(get("/api/interview/" + sessionId + "/report")));
        assertThat(mapper.readTree(againResp).get("reportId").asLong())
                .isEqualTo(report.get("reportId").asLong());

        // 8) end 后新订阅者仍能重放到 report + done（历史事件不丢）
        String streamAfterEnd = body(mvc.perform(get("/api/interview/" + sessionId + "/events")));
        assertThat(streamAfterEnd)
                .contains("event:report")
                .contains("event:done");

        // 9) 会话已 DONE 再作答 → 409（api.md §1 状态冲突）
        int conflict = mvc.perform(post("/api/interview/" + sessionId + "/answer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"answer\":\"再答一次\"}"))
                .andReturn().getResponse().getStatus();
        assertThat(conflict).isEqualTo(409);
    }

    @Test
    void materialCrud_works() throws Exception {
        // 项目文档：建 + 改 + 列
        String created = body(mvc.perform(post("/api/project-doc")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"AI 情报\",\"content\":\"基于 RSS 聚合 + LLM 摘要的技术情报系统\"}")));
        long docId = mapper.readTree(created).get("docId").asLong();

        String listed = body(mvc.perform(get("/api/project-doc")));
        JsonNode arr = mapper.readTree(listed);
        assertThat(arr).hasSize(1);
        assertThat(arr.get(0).get("title").asText()).isEqualTo("AI 情报");

        body(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .put("/api/project-doc/" + docId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"补充现状：已接入 12 个源，日更 30 条\"}")));
        String listed2 = body(mvc.perform(get("/api/project-doc")));
        assertThat(mapper.readTree(listed2).get(0).get("updatedAt").asText()).isNotBlank();

        // 简历：第一份自动启用
        String resume = body(mvc.perform(post("/api/resume")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"# 张三\\nJava 后端\"}")));
        long resumeId = mapper.readTree(resume).get("resumeId").asLong();
        assertThat(mapper.readTree(resume).get("isActive").asBoolean()).isTrue();

        // 笔记
        String note = body(mvc.perform(post("/api/note")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"回表=二级索引拿主键再查聚簇索引\"}")));
        assertThat(mapper.readTree(note).get("noteId").asLong()).isPositive();

        // 错题本：T8 前为空，接口应正常返回空数组而不是报错
        String weak = body(mvc.perform(get("/api/weak-point")));
        assertThat(mapper.readTree(weak)).isEmpty();

        assertThat(resumeId).isPositive();
    }

    @Test
    void sessionNotFound_returns404() throws Exception {
        int status = mvc.perform(get("/api/interview/999999/status"))
                .andReturn().getResponse().getStatus();
        assertThat(status).isEqualTo(404);
    }

    // ---------- 辅助 ----------

    /** 按 UTF-8 读取响应体（MockHttpServletResponse 默认 ISO-8859-1，会毁掉中文）。 */
    private static String body(ResultActions actions) throws Exception {
        return actions.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private void stubLlm() {
        // 出题 / 追问：非思考模式
        when(llm.followUp(anyString(), anyString()))
                .thenReturn(new LlmReply("第一道题：说说 InnoDB 回表是什么？", null));
        // 判分：4 分（0-8），触发 1 层追问
        when(llm.score(anyString(), anyString()))
                .thenReturn(new ScoreResult(4,
                        Map.of("正确性", 2, "完整性", 1, "深度", 1, "实证", 0),
                        List.of("回表本质是聚簇索引查找"),
                        "那覆盖索引是怎么避免回表的？"));
        // chat：JD 解析与 Critic 报告走同一个入口，按 system 提示区分返回
        org.mockito.stubbing.Answer<LlmReply> chatAnswer = inv -> {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> messages = inv.getArgument(2);
            String system = String.valueOf(messages.get(0).get("content"));
            if (system.contains("招聘 JD 分析助手")) {
                return new LlmReply("{\"keywords\":[\"MySQL\",\"Redis\",\"并发\"],\"jobType\":\"Java 后端\"}", null);
            }
            return new LlmReply("""
                    {"matchScore":42,
                     "perTurnReview":[{"turnSeq":1,"question":"回表是什么","score":4,
                        "explanation":"二级索引只存主键","correction":"没提覆盖索引",
                        "knowledgeSupplement":"ICP/延迟关联","exemplarAnswer":"回表就是..."}],
                     "collapsedPoints":["覆盖索引"],
                     "expressionIssues":[{"issue":"只背结论","guide":"加数字"}],
                     "overallNotes":"整体还行",
                     "reminders":["多练"]}
                    """, null);
        };
        // CriticService 现在调用带 maxTokens 的 6 参重载，两个重载都要桩上
        when(llm.chat(any(), anyBoolean(), any(), any(), anyBoolean())).thenAnswer(chatAnswer);
        when(llm.chat(any(), anyBoolean(), any(), any(), anyBoolean(), anyInt())).thenAnswer(chatAnswer);
    }
}
