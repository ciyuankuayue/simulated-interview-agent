package com.bupt.mockinterview.it;

import com.bupt.mockinterview.llm.LlmPort;
import com.bupt.mockinterview.llm.LlmReply;
import com.bupt.mockinterview.llm.chat.ModelRole;
import com.bupt.mockinterview.rag.Embedder;
import com.bupt.mockinterview.rag.Embedding;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.junit.jupiter.EnabledIf;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * R5-1：多租户隔离在真实 MySQL InnoDB 下的回归。
 * <p>H2 共享内存与 InnoDB 独立表空间的隔离语义可能不同；本类用 A/B 双真实账号走
 * 完整 HTTP + JWT 链路，断言跨租户 404/空 + 旁路 raw tenant_id 正确。
 * <p>LLM/Embedder 全 mock（不烧 token）；project-doc 走 RAG 入库，mock Embedder 返回零向量。
 */
@EnabledIf("#{T(com.bupt.mockinterview.it.MysqlItSupport).isMysqlReachable()}")
class IsolationMysqlIntegrationTest extends MysqlItSupport {

    @Autowired private ObjectMapper mapper;

    @MockBean private LlmPort llm;
    @MockBean private Embedder embedder;

    private UserAndToken register(String name, String pwd) throws Exception {
        var resp = mvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + name + "\",\"password\":\"" + pwd + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode body = mapper.readTree(resp.getResponse().getContentAsString());
        long userId = body.get("userId").asLong();
        String token = body.get("token").asText();
        return new UserAndToken(userId, name, token);
    }

    private long createProjectDoc(UserAndToken u, String title, String content) throws Exception {
        var resp = mvc.perform(post("/api/project-doc")
                        .header("Authorization", "Bearer " + u.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"" + title + "\",\"content\":\"" + content + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return mapper.readTree(resp.getResponse().getContentAsString()).get("docId").asLong();
    }

    @Test
    void crossTenantProjectDoc_isolated_onInnoDB() throws Exception {
        // project-doc 入库走 RAG：mock Embedder 返回 1024 维零向量（隔离由 userId pre-filter 保证，
        // 与向量内容无关）
        float[] zero = new float[1024];
        when(embedder.name()).thenReturn("fake:it");
        when(embedder.embed(anyString())).thenReturn(new Embedding(zero, 1L, false));

        String tag = UUID.randomUUID().toString().substring(0, 8);
        UserAndToken a = register("r5-iso-a-" + tag, "secret123");
        UserAndToken b = register("r5-iso-b-" + tag, "secret123");

        long aDocId = createProjectDoc(a, "A 的项目", "A 的核心项目资料，仅 A 可见");

        // A 列表含自己的文档
        var aList = mvc.perform(get("/api/project-doc").header("Authorization", "Bearer " + a.token()))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(mapper.readTree(aList.getResponse().getContentAsString())).hasSize(1);

        // B 列表为空
        var bList = mvc.perform(get("/api/project-doc").header("Authorization", "Bearer " + b.token()))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(mapper.readTree(bList.getResponse().getContentAsString())).isEmpty();

        // B 猜测 A 的文档 id 试图改写 → 404（非 403）
        mvc.perform(put("/api/project-doc/" + aDocId)
                        .header("Authorization", "Bearer " + b.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"B 试图篡改\"}"))
                .andExpect(status().isNotFound());

        // 旁路：A 的 project_doc.tenant_id 必须等于 A.userId（@TenantId 分区谓词生效）
        Long rawTenant = rawTenantIdOf("project_doc", aDocId);
        assertThat(rawTenant).isEqualTo(a.userId());
        assertThat(rawTenant).isNotEqualTo(b.userId());
    }

    record UserAndToken(long userId, String username, String token) {}
}
