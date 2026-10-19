package com.bupt.mockinterview.web;

import com.bupt.mockinterview.billing.UsageScene;
import com.bupt.mockinterview.domain.LlmUsageLog;
import com.bupt.mockinterview.domain.Quota;
import com.bupt.mockinterview.domain.User;
import com.bupt.mockinterview.domain.repository.QuotaRepository;
import com.bupt.mockinterview.rag.Embedding;
import com.bupt.mockinterview.rag.Embedder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * R2 批 3：embedding token 计量。
 * 上传笔记 = ingest（切块→embed→入库）：0 余额必须预检拦截为 402（一次外部 embed 都不发）；
 * 有余额时按厂商 usage.total_tokens 实扣并落 EMBED_INGEST 流水。
 *
 * <p>不加类级 @Transactional：扣费走 REQUIRES_NEW 独立事务，外层回滚不退款，
 * 与 QuotaServiceTest 同理；这里在 @BeforeEach 手动把 testuser 额度复位。
 */
@SpringBootTest
@AutoConfigureMockMvc
class EmbeddingQuotaTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper mapper;
    @Autowired
    private User testUser;
    @Autowired
    private QuotaRepository quotaRepo;
    @Autowired
    private com.bupt.mockinterview.billing.QuotaService quotaService;

    @MockBean
    private Embedder embedder;

    @BeforeEach
    void resetQuota() {
        Quota q = quotaRepo.findByUserId(testUser.getId()).orElseThrow();
        q.setUsedTokens(0L);
        quotaRepo.save(q);
    }

    @Test
    void zeroQuota_uploadNote_returns402_andNeverCallsEmbedder() throws Exception {
        Quota q = quotaRepo.findByUserId(testUser.getId()).orElseThrow();
        q.setUsedTokens(q.getTotalTokens());
        quotaRepo.save(q);

        var actions = mvc.perform(post("/api/note")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"synchronized 底层是 monitor 锁。\"}"));

        assertThat(actions.andReturn().getResponse().getStatus()).isEqualTo(402);
        JsonNode body = mapper.readTree(
                actions.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(body.get("code").asText()).isEqualTo("QUOTA_EXHAUSTED");
        assertThat(body.get("message").asText()).contains("内测阶段", "联系工作人员补充额度", "13481264947");
        // 预检发生在发 embed 之前：0 余额时厂商一次都不能被调
        verify(embedder, never()).embed(anyString());
    }

    @Test
    void uploadNote_chargesRealEmbeddingTokens_andWritesUsageLog() throws Exception {
        // 一段短文本只切 1 个 chunk → 恰好一次 embed、一笔流水
        when(embedder.name()).thenReturn("dashscope:qwen-test");
        when(embedder.embed(anyString()))
                .thenReturn(new Embedding(new float[8], 12L, false));

        var actions = mvc.perform(post("/api/note")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"MySQL 索引使用 B+ 树，二级索引回表。\"}"));

        assertThat(actions.andReturn().getResponse().getStatus()).isEqualTo(200);

        Quota q = quotaRepo.findByUserId(testUser.getId()).orElseThrow();
        assertThat(q.getUsedTokens()).isEqualTo(12L);

        List<LlmUsageLog> logs = quotaService.recentUsage(testUser.getId(), 20).stream()
                .filter(l -> l.getScene() == UsageScene.EMBED_INGEST)
                .toList();
        assertThat(logs).hasSize(1);
        LlmUsageLog log = logs.get(0);
        assertThat(log.getTotalTokens()).isEqualTo(12L);
        assertThat(log.getChargedTokens()).isEqualTo(12L);
        assertThat(log.isEstimated()).isFalse();
        assertThat(log.getKeyMode()).isEqualTo(com.bupt.mockinterview.domain.KeyMode.PLATFORM);
    }
}
