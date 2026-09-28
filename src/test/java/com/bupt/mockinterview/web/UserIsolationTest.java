package com.bupt.mockinterview.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * R1-1 多用户隔离 E2E：开启 JWT，注册两个用户，验证数据互相不可见。
 *
 * 这是 R1-1 的核心验收——没有它，48 个禁用 JWT 的旧测试全部跑绿
 * 也不能证明多用户隔离真的工作。
 *
 * 覆盖场景：
 * 1. 未带 token → 401
 * 2. 带 A 的 token，创建 JD → A 可见
 * 3. B 的 token 查 JD 列表 → 空（A 的数据隔离）
 * 4. B 访问 A 的 JD / Session → 404（ownership 验证）
 * 5. username 重复注册 → 409
 * 6. 错误密码登录 → 401
 */
@SpringBootTest(properties = "app.auth.jwt-enabled=true")
@AutoConfigureMockMvc
@Transactional
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class UserIsolationTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper mapper;

    private String tokenA;
    private String tokenB;

    @BeforeEach
    void setUp() throws Exception {
        // 注册用户 A
        ResultActions regA = mvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"alice\",\"password\":\"alice123\"}"));
        assertThat(regA.andReturn().getResponse().getStatus()).isEqualTo(201);
        tokenA = extractToken(regA);

        // 注册用户 B
        ResultActions regB = mvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"bobby\",\"password\":\"bob12345\"}"));
        assertThat(regB.andReturn().getResponse().getStatus()).isEqualTo(201);
        tokenB = extractToken(regB);

        assertThat(tokenA).isNotBlank();
        assertThat(tokenB).isNotBlank();
        assertThat(tokenA).isNotEqualTo(tokenB);
    }

    // ==================== 1. JWT 鉴权基础 ====================

    @Test
    void noToken_returns401() throws Exception {
        mvc.perform(get("/api/jd"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void invalidToken_returns401() throws Exception {
        mvc.perform(get("/api/jd").header("Authorization", "Bearer totally-invalid-token"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void authWhitelist_noToken_allowed() throws Exception {
        // register/login 不放 token 也能访问
        mvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"charlie\",\"password\":\"charlie123\"}"))
                .andExpect(status().isCreated());

        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"alice\",\"password\":\"alice123\"}"))
                .andExpect(status().isOk());
    }

    // ==================== 2. 注册/登录边界 ====================

    @Test
    void duplicateUsername_registerReturns409() throws Exception {
        mvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"alice\",\"password\":\"another123\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("USERNAME_TAKEN"));
    }

    @Test
    void wrongPassword_loginReturns401() throws Exception {
        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"alice\",\"password\":\"wrongpassword\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    @Test
    void nonexistentUser_loginReturns401() throws Exception {
        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"ghost\",\"password\":\"ghost123\"}"))
                .andExpect(status().isUnauthorized());
    }

    // ==================== 3. 数据隔离（核心验收） ====================

    @Test
    void jdData_isolatedBetweenUsers() throws Exception {
        // A 创建一条 JD
        ResultActions create = mvc.perform(post("/api/jd")
                .header("Authorization", "Bearer " + tokenA)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"rawText\":\"Java 后端岗位，要求 MySQL 经验\"}"));
        create.andExpect(status().isOk());
        long jdId = extractFirstLong(create, "jdId");

        // A 能查到
        ResultActions listA = mvc.perform(get("/api/jd").header("Authorization", "Bearer " + tokenA));
        listA.andExpect(status().isOk());
        JsonNode listANode = mapper.readTree(listA.andReturn().getResponse().getContentAsString());
        assertThat(listANode.isArray()).isTrue();
        assertThat(listANode.size()).isEqualTo(1);
        assertThat(listANode.get(0).get("jdId").asLong()).isEqualTo(jdId);

        // B 查不到 A 的 JD（隔离！）
        ResultActions listB = mvc.perform(get("/api/jd").header("Authorization", "Bearer " + tokenB));
        listB.andExpect(status().isOk());
        JsonNode listBNode = mapper.readTree(listB.andReturn().getResponse().getContentAsString());
        assertThat(listBNode.isArray()).isTrue();
        assertThat(listBNode.size()).isEqualTo(0);
    }

    @Test
    void resumeData_isolatedBetweenUsers() throws Exception {
        // A 创建简历
        ResultActions create = mvc.perform(post("/api/resume")
                .header("Authorization", "Bearer " + tokenA)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"# 张三\\nJava 后端\"}"));
        create.andExpect(status().isOk());

        // A 能看到
        ResultActions listA = mvc.perform(get("/api/resume").header("Authorization", "Bearer " + tokenA));
        listA.andExpect(status().isOk());
        assertThat(mapper.readTree(listA.andReturn().getResponse().getContentAsString()).size()).isEqualTo(1);

        // B 看不到
        ResultActions listB = mvc.perform(get("/api/resume").header("Authorization", "Bearer " + tokenB));
        listB.andExpect(status().isOk());
        assertThat(mapper.readTree(listB.andReturn().getResponse().getContentAsString()).size()).isEqualTo(0);
    }

    @Test
    void crossUser_jdAccess_returns404() throws Exception {
        // A 创建 JD
        ResultActions create = mvc.perform(post("/api/jd")
                .header("Authorization", "Bearer " + tokenA)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"rawText\":\"Java 后端岗位\"}"));
        long jdId = extractFirstLong(create, "jdId");

        // B 用这个 JD 去 start 面试 → 404（MaterialController 查 JD 时按 user 过滤找不到）
        mvc.perform(post("/api/interview/start")
                        .header("Authorization", "Bearer " + tokenB)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jdId\":" + jdId + "}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void crossUser_sessionAccess_returns404() throws Exception {
        // A 创建 JD + 开始面试
        ResultActions createJd = mvc.perform(post("/api/jd")
                .header("Authorization", "Bearer " + tokenA)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"rawText\":\"Java 后端岗位\"}"));
        long jdId = extractFirstLong(createJd, "jdId");

        ResultActions start = mvc.perform(post("/api/interview/start")
                .header("Authorization", "Bearer " + tokenA)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"jdId\":" + jdId + ",\"roles\":[\"技术深挖官\"]}"));
        start.andExpect(status().isOk());
        long sessionId = extractLong(start, "sessionId");

        // B 访问 A 的 session → 404（InterviewController 按 user 过滤找不到）
        mvc.perform(get("/api/interview/" + sessionId + "/status")
                        .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isNotFound());

        mvc.perform(get("/api/interview/" + sessionId + "/report")
                        .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isNotFound());
    }

    // ==================== 4. 同用户全链路 ====================

    @Test
    void sameUser_fullFlow_works() throws Exception {
        // A 创建 JD → 开始 → 状态查询
        ResultActions create = mvc.perform(post("/api/jd")
                .header("Authorization", "Bearer " + tokenA)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"rawText\":\"Java 后端岗位\"}"));
        long jdId = extractFirstLong(create, "jdId");

        ResultActions start = mvc.perform(post("/api/interview/start")
                .header("Authorization", "Bearer " + tokenA)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"jdId\":" + jdId + "}"));
        start.andExpect(status().isOk());

        long sessionId = extractLong(start, "sessionId");
        mvc.perform(get("/api/interview/" + sessionId + "/status")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value(sessionId));
    }

    // ==================== 辅助 ====================

    private String extractToken(ResultActions actions) throws Exception {
        JsonNode root = mapper.readTree(actions.andReturn().getResponse().getContentAsString());
        return root.path("token").asText();
    }

    private long extractLong(ResultActions actions, String field) throws Exception {
        JsonNode root = mapper.readTree(actions.andReturn().getResponse().getContentAsString());
        return root.path(field).asLong();
    }

    /** 从数组响应提取第一个元素的指定字段值。 */
    private long extractFirstLong(ResultActions actions, String field) throws Exception {
        JsonNode root = mapper.readTree(actions.andReturn().getResponse().getContentAsString());
        return root.get(0).path(field).asLong();
    }
}
