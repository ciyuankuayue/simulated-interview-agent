package com.bupt.mockinterview.web;

import com.bupt.mockinterview.domain.User;
import com.bupt.mockinterview.domain.UserRole;
import com.bupt.mockinterview.domain.repository.UserRepository;
import com.bupt.mockinterview.rag.VectorStore;
import com.bupt.mockinterview.security.TenantContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * R2-T0 批 5（C3）Web 层契约：孤儿巡检/回收端点的鉴权三件套（401/403/200）
 * 与 warn-first 回收语义（200/404/409/400）。
 * 开 JWT（同 UserIsolationTest 口径）：注册真实用户、ADMIN 经 repository 提权；
 * 无类级 @Transactional + UUID 名 + {@link DirtiesContext}（注册/REQUIRES_NEW 真实提交）。
 */
@SpringBootTest(properties = "app.auth.jwt-enabled=true")
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AdminIndexApiTest {

    private static final int DIM = 1024;

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private UserRepository userRepo;
    @Autowired private VectorStore store;
    @Autowired private com.bupt.mockinterview.domain.repository.KnowledgeChunkRepository chunkRepo;

    private String tokenUser;
    private String tokenAdmin;
    private long adminId;

    private JsonNode json(ResultActions a) throws Exception {
        return mapper.readTree(a.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private ResultActions register(String name, String pwd) throws Exception {
        return mvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + name + "\",\"password\":\"" + pwd + "\"}"));
    }

    private String tokenOf(ResultActions reg) throws Exception {
        return reg.andReturn().getResponse().getHeader("Authorization") != null
                ? reg.andReturn().getResponse().getHeader("Authorization").replace("Bearer ", "")
                : json(reg).get("token").asText();
    }

    @BeforeEach
    void setUp() throws Exception {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        tokenUser = tokenOf(register("idx-user-" + tag, "user12345"));

        String adminName = "idx-admin-" + tag;
        ResultActions regAdmin = register(adminName, "admin12345");
        assertThat(regAdmin.andReturn().getResponse().getStatus()).isEqualTo(201);
        tokenAdmin = tokenOf(regAdmin);
        User admin = userRepo.findByUsername(adminName).orElseThrow();
        admin.setRole(UserRole.ADMIN);
        userRepo.save(admin);
        adminId = admin.getId();
    }

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    /** 直接往 ram 索引写 n 个属于指定用户的向量块（不经 embedder/计费）。 */
    private void seedVectors(Long userId, String sourceType, String sourceId, int n) {
        store.beginReplace(userId, sourceType, sourceId);
        for (int i = 0; i < n; i++) {
            float[] vec = new float[DIM];
            vec[i] = 1f;
            store.add(new com.bupt.mockinterview.rag.KnowledgeChunk(
                    sourceType + "#" + sourceId + "#" + i, userId, sourceType, sourceId,
                    "WEB_SEED " + sourceType + "#" + sourceId + "#" + i, vec));
        }
        store.commitBatch();
    }

    @Test
    void noToken_orphans_returns401() throws Exception {
        mvc.perform(get("/api/admin/index/orphans"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        mvc.perform(post("/api/admin/index/delete-by-source")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tenantId\":1,\"sourceType\":\"note\",\"sourceId\":\"1\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void nonAdmin_returns403() throws Exception {
        mvc.perform(get("/api/admin/index/orphans").header("Authorization", "Bearer " + tokenUser))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mvc.perform(post("/api/admin/index/delete-by-source")
                        .header("Authorization", "Bearer " + tokenUser)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tenantId\":" + adminId + ",\"sourceType\":\"note\",\"sourceId\":\"1\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void admin_orphans_scopesAndShape_ok() throws Exception {
        ResultActions all = mvc.perform(get("/api/admin/index/orphans")
                .header("Authorization", "Bearer " + tokenAdmin));
        all.andExpect(status().isOk())
                .andExpect(jsonPath("$.scope").value("ALL"))
                .andExpect(jsonPath("$.orphans").isArray());

        ResultActions scoped = mvc.perform(get("/api/admin/index/orphans")
                .header("Authorization", "Bearer " + tokenAdmin)
                .param("tenantId", String.valueOf(adminId)));
        scoped.andExpect(status().isOk())
                .andExpect(jsonPath("$.scope").value("TENANT"))
                .andExpect(jsonPath("$.count").isNumber());
    }

    @Test
    void admin_deleteOrphan_fullFlow_thenSecondDelete404() throws Exception {
        seedVectors(adminId, "note", "7001", 2);
        JsonNode orphans = json(mvc.perform(get("/api/admin/index/orphans")
                .header("Authorization", "Bearer " + tokenAdmin)
                .param("tenantId", String.valueOf(adminId))));
        boolean seen = false;
        for (JsonNode o : orphans.get("orphans")) {
            if ("7001".equals(o.get("sourceId").asText())) {
                seen = true;
                assertThat(o.get("indexedChunks").asInt()).isEqualTo(2);
                assertThat(o.get("reason").asText()).isEqualTo("VECTOR_WITHOUT_CHUNK");
            }
        }
        assertThat(seen).as("巡检应报告 7001 孤儿").isTrue();

        ResultActions deleted = mvc.perform(post("/api/admin/index/delete-by-source")
                .header("Authorization", "Bearer " + tokenAdmin)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"tenantId\":" + adminId + ",\"sourceType\":\"note\",\"sourceId\":\"7001\"}"));
        deleted.andExpect(status().isOk())
                .andExpect(jsonPath("$.deleted").value(true))
                .andExpect(jsonPath("$.chunksRemoved").value(2));

        assertThat(store.enumerateSources(adminId)).noneMatch(s -> "7001".equals(s.sourceId()));

        // 再删：索引已无此来源 → 404
        mvc.perform(post("/api/admin/index/delete-by-source")
                        .header("Authorization", "Bearer " + tokenAdmin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tenantId\":" + adminId + ",\"sourceType\":\"note\",\"sourceId\":\"7001\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void admin_deleteHealthySource_returns409_andKeepsVectors() throws Exception {
        seedVectors(adminId, "note", "7002", 1);
        // 补一行知识块：健康来源，不许按孤儿删
        persistHealthyRow(userRepo.findById(adminId).orElseThrow());

        mvc.perform(post("/api/admin/index/delete-by-source")
                        .header("Authorization", "Bearer " + tokenAdmin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tenantId\":" + adminId + ",\"sourceType\":\"note\",\"sourceId\":\"7002\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));

        assertThat(store.enumerateSources(adminId))
                .anyMatch(s -> "7002".equals(s.sourceId()) && s.chunks() == 1);
    }

    private void persistHealthyRow(User admin) {
        TenantContext.set(adminId);
        try {
            com.bupt.mockinterview.domain.KnowledgeChunk row = new com.bupt.mockinterview.domain.KnowledgeChunk();
            row.setUser(admin);
            row.setSourceType("note");
            row.setSourceId(7002L);
            row.setChunkText("healthy");
            chunkRepo.save(row);
            chunkRepo.flush();
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void admin_delete_missingTenantId_returns400() throws Exception {
        mvc.perform(post("/api/admin/index/delete-by-source")
                        .header("Authorization", "Bearer " + tokenAdmin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceType\":\"note\",\"sourceId\":\"1\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }
}
