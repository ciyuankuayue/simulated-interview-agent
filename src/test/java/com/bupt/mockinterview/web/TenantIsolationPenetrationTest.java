package com.bupt.mockinterview.web;

import com.bupt.mockinterview.domain.User;
import com.bupt.mockinterview.domain.WeakPoint;
import com.bupt.mockinterview.domain.repository.UserRepository;
import com.bupt.mockinterview.domain.repository.WeakPointRepository;
import com.bupt.mockinterview.llm.ApiKeyStatus;
import com.bupt.mockinterview.llm.LlmPort;
import com.bupt.mockinterview.llm.LlmReply;
import com.bupt.mockinterview.llm.ScoreResult;
import com.bupt.mockinterview.rag.Embedding;
import com.bupt.mockinterview.rag.Embedder;
import com.bupt.mockinterview.security.TenantContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * R2-T0 批 6：多租户渗透矩阵（jwt=true，A/B 双真实账号）。
 * <p>原则：每个端点都要证明「换个 token 就 404/空，身份资源只随 JWT 走」，
 * 外加向量检索跨租户零泄露（确定性 Embedder + followUp prompt 捕获）与 ADMIN 403 矩阵。
 * LLM/Embedder 全 stub 不烧 token；无类级 @Transactional + UUID 名 + DirtiesContext。
 * strict 清空上下文直调 service 的 fail-closed 由 StrictVectorGuardTest 独立覆盖，不重复。
 */
@SpringBootTest(properties = "app.auth.jwt-enabled=true")
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class TenantIsolationPenetrationTest {

    private static final int DIM = 1024;
    private static final String SECRET = "sk-penetration-0000000000000000abcd";

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private UserRepository userRepo;
    @Autowired private WeakPointRepository weakPointRepo;

    @MockBean private LlmPort llm;
    @MockBean private Embedder embedder;

    private String tokenA;
    private String tokenB;
    private long idA;
    private long idB;

    // ---------- 基建 ----------

    private JsonNode json(ResultActions a) throws Exception {
        return mapper.readTree(a.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private ResultActions register(String name, String pwd) throws Exception {
        return mvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + name + "\",\"password\":\"" + pwd + "\"}"));
    }

    private String tokenOf(ResultActions reg) throws Exception {
        return json(reg).get("token").asText();
    }

    private ResultActions authGet(String token, String url) throws Exception {
        return mvc.perform(get(url).header("Authorization", "Bearer " + token));
    }

    private ResultActions authPostJson(String token, String url, String body) throws Exception {
        return mvc.perform(post(url).header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions authPutJson(String token, String url, String body) throws Exception {
        return mvc.perform(put(url).header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private long firstJdId(ResultActions createJd) throws Exception {
        JsonNode arr = json(createJd);
        return arr.get(0).get("jdId").asLong();
    }

    private long createJd(String token, String rawText) throws Exception {
        return firstJdId(authPostJson(token, "/api/jd", "{\"rawText\":\"" + rawText + "\"}"));
    }

    private float[] basis(int axis) {
        float[] v = new float[DIM];
        v[axis] = 1f;
        return v;
    }

    @BeforeEach
    void setUp() throws Exception {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        tokenA = tokenOf(register("pen-a-" + tag, "alice12345"));
        tokenB = tokenOf(register("pen-b-" + tag, "bobby12345"));
        idA = userRepo.findByUsername("pen-a-" + tag).orElseThrow().getId();
        idB = userRepo.findByUsername("pen-b-" + tag).orElseThrow().getId();

        // 确定性向量：含约定关键词的文本走 axis3（A 的机密资料与 A 的 JD 同向），其余 axis7
        when(embedder.name()).thenReturn("fake:penetration");
        when(embedder.embed(anyString())).thenAnswer(inv -> {
            String text = inv.getArgument(0, String.class);
            return new Embedding(text.contains("ZANZIBAR") ? basis(3) : basis(7), 1L, false);
        });

        when(llm.verifyApiKey(anyString())).thenReturn(ApiKeyStatus.VALID);
        when(llm.followUp(anyString(), anyString()))
                .thenReturn(new LlmReply("渗透用首题：请做个自我介绍？", null));
        when(llm.score(any(), anyString(), anyString()))
                .thenReturn(new ScoreResult(7,
                        Map.of("正确性", 3, "完整性", 2, "深度", 1, "实证", 1),
                        List.of(), "渗透判分无需追问"));
        org.mockito.stubbing.Answer<LlmReply> chatAnswer = inv -> {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> messages = inv.getArgument(2);
            String system = String.valueOf(messages.get(0).get("content"));
            if (system.contains("招聘 JD 分析助手")) {
                return new LlmReply("{\"keywords\":[\"Java\",\"MySQL\"],\"jobType\":\"Java 后端\"}", null);
            }
            return new LlmReply("""
                    {"matchScore":42,
                     "perTurnReview":[{"turnSeq":1,"question":"q","score":7,
                        "explanation":"e","correction":"c","knowledgeSupplement":"k","exemplarAnswer":"x"}],
                     "collapsedPoints":[],"expressionIssues":[],"overallNotes":"n","reminders":[]}
                    """, null);
        };
        when(llm.chat(any(), anyBoolean(), any(), any(), anyBoolean())).thenAnswer(chatAnswer);
        when(llm.chat(any(), anyBoolean(), any(), any(), anyBoolean(), anyInt())).thenAnswer(chatAnswer);
        when(llm.chat(any(), anyBoolean(), any(), any(), anyBoolean(), anyInt(), any())).thenAnswer(chatAnswer);
    }

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    // ---------- 1. 资料列表跨租户 ----------

    @Test
    void materialLists_bSeesNothingOfA() throws Exception {
        authPostJson(tokenA, "/api/resume", "{\"content\":\"# A 的简历\\nJava 三年\"}").andExpect(status().isOk());
        authPostJson(tokenA, "/api/note", "{\"content\":\"A 的私有笔记：回表与索引下推\"}").andExpect(status().isOk());
        authPostJson(tokenA, "/api/project-doc", "{\"title\":\"A 的项目\",\"content\":\"A 的机密项目文档\"}")
                .andExpect(status().isOk());
        authPostJson(tokenA, "/api/real-interview", "{\"content\":\"A 的真实面经记录\"}").andExpect(status().isOk());

        for (String url : List.of("/api/jd", "/api/resume", "/api/note", "/api/project-doc")) {
            JsonNode arr = json(authGet(tokenB, url));
            assertThat(arr.isArray()).as(url + " 必须是数组").isTrue();
            assertThat(arr.size()).as("B 不应看到 A 的 " + url).isZero();
        }
        // A 自己三个列表有数据
        assertThat(json(authGet(tokenA, "/api/resume")).size()).isEqualTo(1);
        assertThat(json(authGet(tokenA, "/api/note")).size()).isEqualTo(1);
        assertThat(json(authGet(tokenA, "/api/project-doc")).size()).isEqualTo(1);
    }

    // ---------- 2. 资源 id 猜测攻击 ----------

    @Test
    void resourceIds_unguessable_crossTenantAll404() throws Exception {
        long jdId = createJd(tokenA, "Java 岗位，MySQL");
        long resumeId = json(authPostJson(tokenA, "/api/resume", "{\"content\":\"A 简历\"}"))
                .get("resumeId").asLong();
        long docId = json(authPostJson(tokenA, "/api/project-doc",
                "{\"title\":\"t\",\"content\":\"A 项目文档内容\"}")).get("docId").asLong();

        // B 拿 A 的 id 直接操作：start / 激活简历 / 改文档全部 404
        authPostJson(tokenB, "/api/interview/start", "{\"jdId\":" + jdId + "}")
                .andExpect(status().isNotFound());
        authPostJson(tokenB, "/api/resume/" + resumeId + "/activate", "{}")
                .andExpect(status().isNotFound());
        authPutJson(tokenB, "/api/project-doc/" + docId, "{\"content\":\"B 试图改 A 的文档\"}")
                .andExpect(status().isNotFound());

        // A 自己操作自己的资源正常
        authPostJson(tokenA, "/api/resume/" + resumeId + "/activate", "{}")
                .andExpect(status().isOk());
    }

    // ---------- 3. 会话全家桶跨租户 ----------

    @Test
    void sessionMatrix_crossTenant_404_andSessionsScoped() throws Exception {
        long jdId = createJd(tokenA, "Java 岗位，Redis 缓存");
        long sessionId = json(authPostJson(tokenA, "/api/interview/start",
                "{\"jdId\":" + jdId + ",\"roles\":[\"技术深挖官\"]}")).get("sessionId").asLong();

        // B 对 A 的会话：status/report/answer/end/SSE 全部 404
        authGet(tokenB, "/api/interview/" + sessionId + "/status").andExpect(status().isNotFound());
        authGet(tokenB, "/api/interview/" + sessionId + "/report").andExpect(status().isNotFound());
        authPostJson(tokenB, "/api/interview/" + sessionId + "/answer", "{\"answer\":\"B 冒答\"}")
                .andExpect(status().isNotFound());
        authPostJson(tokenB, "/api/interview/" + sessionId + "/end", "{}")
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/interview/" + sessionId + "/events").param("jwt", tokenB))
                .andExpect(status().isNotFound());

        // B 的历史会话不含 A 的场
        JsonNode bSessions = json(authGet(tokenB, "/api/interview/sessions"));
        assertThat(bSessions.isArray()).isTrue();
        assertThat(bSessions.size()).isZero();

        // A 自己可见可查
        JsonNode aSessions = json(authGet(tokenA, "/api/interview/sessions"));
        assertThat(aSessions.size()).isEqualTo(1);
        assertThat(aSessions.get(0).get("sessionId").asLong()).isEqualTo(sessionId);
        authGet(tokenA, "/api/interview/" + sessionId + "/status")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value(sessionId));
    }

    // ---------- 4. 错题本隔离 ----------

    @Test
    void weakPoints_crossTenant_404AndQueueScoped() throws Exception {
        long wpId;
        TenantContext.set(idA);
        try {
            WeakPoint wp = new WeakPoint();
            wp.setUser(userRepo.findById(idA).orElseThrow());
            wp.setKnowledgePoint("A 独有的知识盲点：MVCC ReadView");
            wp.setWrongCount(2);
            wp.setReviewStage(0);
            wp.setResolved(false);
            wp.setStatus("答得含糊");
            wp.setCreatedAt(Instant.now());
            wp.setNextReviewAt(Instant.now().minus(1, ChronoUnit.HOURS));
            wpId = weakPointRepo.save(wp).getId();
        } finally {
            TenantContext.clear();
        }

        assertThat(json(authGet(tokenB, "/api/weak-point")).size()).isZero();
        assertThat(json(authGet(tokenB, "/api/weak-point/due")).size()).isZero();

        // B 拿 A 的错题本 id（自增 id 可猜）→ 404
        authGet(tokenB, "/api/weak-point/" + wpId).andExpect(status().isNotFound());

        // A 队列/到期/详情都在
        assertThat(json(authGet(tokenA, "/api/weak-point")).size()).isEqualTo(1);
        assertThat(json(authGet(tokenA, "/api/weak-point/due")).size()).isEqualTo(1);
        authGet(tokenA, "/api/weak-point/" + wpId).andExpect(status().isOk())
                .andExpect(jsonPath("$.knowledgePoint").value("A 独有的知识盲点：MVCC ReadView"));
    }

    // ---------- 5. 身份型端点只认 JWT 本人 ----------

    @Test
    void identityEndpoints_eachOnlySeesOwn() throws Exception {
        // A 入库一条笔记 → A 有 EMBED_INGEST 流水
        authPostJson(tokenA, "/api/note", "{\"content\":\"A 的流水隔离验证笔记\"}").andExpect(status().isOk());
        assertThat(json(authGet(tokenA, "/api/user/usage")).size()).isGreaterThanOrEqualTo(1);
        assertThat(json(authGet(tokenB, "/api/user/usage")).size()).isZero();

        // 额度视图：B 的 used 必须为 0（A 的 embedding 结算不能串到 B）
        assertThat(json(authGet(tokenB, "/api/user/quota")).get("usedTokens").asLong()).isZero();
        assertThat(json(authGet(tokenA, "/api/user/quota")).get("usedTokens").asLong())
                .isGreaterThanOrEqualTo(1);

        // 设置互不影响：B 切 QWEN，A 仍 DEEPSEEK
        authPutJson(tokenB, "/api/user/settings", "{\"chatProvider\":\"QWEN\"}").andExpect(status().isOk());
        assertThat(json(authGet(tokenA, "/api/user/settings")).get("chatProvider").asText())
                .isEqualTo("DEEPSEEK");

        // API Key：B 存了 key，A 仍无 key
        authPutJson(tokenB, "/api/user/api-key", "{\"apiKey\":\"" + SECRET + "\"}")
                .andExpect(status().isOk());
        assertThat(json(authGet(tokenB, "/api/user/api-key")).get("hasKey").asBoolean()).isTrue();
        assertThat(json(authGet(tokenA, "/api/user/api-key")).get("hasKey").asBoolean()).isFalse();
    }

    // ---------- 6. ADMIN 矩阵 ----------

    @Test
    void adminEndpoints_normalUsersAll403() throws Exception {
        for (String t : List.of(tokenA, tokenB)) {
            authPostJson(t, "/api/admin/quota", "{\"username\":\"x\",\"grantTokens\":1}")
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("FORBIDDEN"));
            authGet(t, "/api/admin/index/orphans")
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("FORBIDDEN"));
            authPostJson(t, "/api/admin/index/delete-by-source",
                            "{\"tenantId\":1,\"sourceType\":\"note\",\"sourceId\":\"1\"}")
                    .andExpect(status().isForbidden());
        }
    }

    // ---------- 7. 向量检索跨租户零泄露 + A 本人全链路 200 ----------

    @Test
    void vectorRetrieval_bInterviewNeverSeesA_andAFullChain200() throws Exception {
        // A 的机密笔记（向量 axis3，与含 ZANZIBAR 的查询同向）
        authPostJson(tokenA, "/api/note",
                "{\"content\":\"PEN_A_SECRET ZANZIBAR 这是 A 的机密项目复盘，外人绝不可见\"}")
                .andExpect(status().isOk());

        // B 的 JD 不含约定词 → 查询 axis7；B 自己无任何资料
        long bJd = createJd(tokenB, "普通运营岗位 JD，要求文档撰写与跨部门沟通");
        authPostJson(tokenB, "/api/interview/start",
                "{\"jdId\":" + bJd + ",\"roles\":[\"技术深挖官\"]}").andExpect(status().isOk());

        // 阶段 1：到 B 的 start 为止，followUp 收到的出题 prompt 不含 A 的机密
        ArgumentCaptor<String> userPrompts = ArgumentCaptor.forClass(String.class);
        verify(llm, atLeastOnce()).followUp(anyString(), userPrompts.capture());
        assertThat(userPrompts.getAllValues())
                .as("B 的出题上下文不得泄露 A 的向量块")
                .isNotEmpty()
                .allSatisfy(p -> assertThat(p).doesNotContain("PEN_A_SECRET", "ZANZIBAR"));

        // A 自己的 JD（含约定词 → 查询 axis3）start：A 必能召回自己的机密块（阳性对照）
        long aJd = createJd(tokenA, "高级 Java 岗位，关键词 ZANZIBAR 一致性协议");
        long aSession = json(authPostJson(tokenA, "/api/interview/start",
                "{\"jdId\":" + aJd + ",\"roles\":[\"技术深挖官\"]}")).get("sessionId").asLong();
        // captor 的 getAllValues() 是上次 verify 的快照，A start 后必须再 verify 一次重新捕获
        verify(llm, atLeastOnce()).followUp(anyString(), userPrompts.capture());
        assertThat(userPrompts.getAllValues().get(userPrompts.getAllValues().size() - 1))
                .as("A 的出题上下文应包含自己的向量块")
                .contains("PEN_A_SECRET");

        // A 本人全链路：answer 200 / end 出报告 / status DONE
        authPostJson(tokenA, "/api/interview/" + aSession + "/answer",
                "{\"answer\":\"我在项目里主导了 ZANZIBAR 协议的落地\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.accepted").value(true));
        authPostJson(tokenA, "/api/interview/" + aSession + "/end", "{}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.matchScore").value(42));
        authGet(tokenA, "/api/interview/" + aSession + "/status")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DONE"));
    }

    // ---------- 8. 无 token 全 401 ----------

    @Test
    void unauthenticated_allProtectedEndpoints_401() throws Exception {
        mvc.perform(get("/api/jd")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/note")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/weak-point")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/user/settings")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/user/quota")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/interview/sessions")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/interview/1/events")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/interview/start").contentType(MediaType.APPLICATION_JSON)
                .content("{\"jdId\":1}")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/admin/quota")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/index/orphans")).andExpect(status().isUnauthorized());
    }
}
