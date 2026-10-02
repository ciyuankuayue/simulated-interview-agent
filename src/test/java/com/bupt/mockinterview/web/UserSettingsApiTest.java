package com.bupt.mockinterview.web;

import com.bupt.mockinterview.billing.QuotaService;
import com.bupt.mockinterview.domain.User;
import com.bupt.mockinterview.llm.ApiKeyStatus;
import com.bupt.mockinterview.llm.LlmPort;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * R1-3 「模型调用方式」契约验收（api.md §7；LLM 验活用 @MockBean，绝不打真实网络）：
 * 默认 PLATFORM；无 key 切 BYOK → 409；key 验活 400/502 不落库；
 * 有效 key 加密落库且明文不回显；切 BYOK 后扣点反映在 quota；删除 key 强制回退 PLATFORM。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class UserSettingsApiTest {

    private static final String SECRET = "sk-byok-secret-key-0000000000abcd";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper mapper;
    @Autowired
    private User testUser;
    @Autowired
    private QuotaService quotaService;

    @MockBean
    private LlmPort llm;

    private JsonNode json(org.springframework.test.web.servlet.ResultActions a) throws Exception {
        return mapper.readTree(a.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private org.springframework.test.web.servlet.ResultActions putJson(String url, String body) throws Exception {
        return mvc.perform(put(url).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @Test
    void fullByokLifecycle_saveSwitchChargeDeleteFallback() throws Exception {
        // ---- 初始：PLATFORM、无 key、有赠点 ----
        assertThat(json(mvc.perform(get("/api/user/settings"))).get("keyMode").asText()).isEqualTo("PLATFORM");
        JsonNode noKey = json(mvc.perform(get("/api/user/api-key")));
        assertThat(noKey.get("hasKey").asBoolean()).isFalse();

        JsonNode quota0 = json(mvc.perform(get("/api/user/quota")));
        assertThat(quota0.get("total").asLong()).isEqualTo(1000L);
        assertThat(quota0.get("used").asLong()).isZero();

        // ---- 无 key 切 BYOK → 409 ----
        var conflictActions = putJson("/api/user/settings", "{\"keyMode\":\"BYOK\"}");
        assertThat(conflictActions.andReturn().getResponse().getStatus()).isEqualTo(409);
        assertThat(json(conflictActions).get("code").asText()).isEqualTo("KEY_NOT_CONFIGURED");

        // ---- 非法 keyMode → 400 ----
        assertThat(putJson("/api/user/settings", "{\"keyMode\":\"XXX\"}").andReturn().getResponse().getStatus())
                .isEqualTo(400);

        // ---- 格式就不对 → 400，不验活 ----
        assertThat(putJson("/api/user/api-key", "{\"apiKey\":\"garbage\"}").andReturn().getResponse().getStatus())
                .isEqualTo(400);

        // ---- 验活拒绝（401/403）→ 400 INVALID_API_KEY，不落库 ----
        when(llm.verifyApiKey(anyString())).thenReturn(ApiKeyStatus.INVALID);
        var invalidActions = putJson("/api/user/api-key", "{\"apiKey\":\"" + SECRET + "\"}");
        assertThat(invalidActions.andReturn().getResponse().getStatus()).isEqualTo(400);
        assertThat(json(invalidActions).get("code").asText()).isEqualTo("INVALID_API_KEY");
        assertThat(json(mvc.perform(get("/api/user/api-key"))).get("hasKey").asBoolean()).isFalse();

        // ---- 服务商不可达 → 502，不落库 ----
        when(llm.verifyApiKey(anyString())).thenReturn(ApiKeyStatus.UNREACHABLE);
        assertThat(putJson("/api/user/api-key", "{\"apiKey\":\"" + SECRET + "\"}")
                .andReturn().getResponse().getStatus()).isEqualTo(502);

        // ---- 验活通过 → 200 落库；GET 只回 hasKey+last4，明文绝不回传 ----
        when(llm.verifyApiKey(anyString())).thenReturn(ApiKeyStatus.VALID);
        var savedActions = putJson("/api/user/api-key", "{\"apiKey\":\"" + SECRET + "\"}");
        assertThat(savedActions.andReturn().getResponse().getStatus()).isEqualTo(200);
        JsonNode saved = json(savedActions);
        assertThat(saved.get("hasKey").asBoolean()).isTrue();
        assertThat(saved.get("last4").asText()).isEqualTo("abcd");

        String keyResp = mvc.perform(get("/api/user/api-key"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(keyResp).doesNotContain(SECRET);
        assertThat(keyResp).contains("abcd");

        // ---- 此时才允许切 BYOK ----
        assertThat(json(putJson("/api/user/settings", "{\"keyMode\":\"BYOK\"}")).get("keyMode").asText())
                .isEqualTo("BYOK");

        // ---- BYOK 扣点：一次调用后 quota 立即可见 ----
        assertThat(quotaService.chargeForByokCall(testUser.getId())).isTrue();
        JsonNode quota1 = json(mvc.perform(get("/api/user/quota")));
        assertThat(quota1.get("used").asLong()).isEqualTo(1L);
        assertThat(quota1.get("remaining").asLong()).isEqualTo(999L);

        // ---- 更换 key（last4 变化）----
        String secret2 = "sk-replaced-key-0000000000000000wxyz";
        when(llm.verifyApiKey(anyString())).thenReturn(ApiKeyStatus.VALID);
        JsonNode replaced = json(putJson("/api/user/api-key", "{\"apiKey\":\"" + secret2 + "\"}"));
        assertThat(replaced.get("last4").asText()).isEqualTo("wxyz");

        // ---- 删除 key：强制回退 PLATFORM ----
        JsonNode deleted = json(mvc.perform(delete("/api/user/api-key")));
        assertThat(deleted.get("hasKey").asBoolean()).isFalse();
        assertThat(deleted.get("keyMode").asText()).isEqualTo("PLATFORM");
        assertThat(json(mvc.perform(get("/api/user/settings"))).get("keyMode").asText()).isEqualTo("PLATFORM");
        assertThat(json(mvc.perform(get("/api/user/api-key"))).get("hasKey").asBoolean()).isFalse();
    }
}
