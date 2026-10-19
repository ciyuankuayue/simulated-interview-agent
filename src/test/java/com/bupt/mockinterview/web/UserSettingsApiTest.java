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
        assertThat(quota0.get("totalTokens").asLong()).isEqualTo(50_000L);
        assertThat(quota0.get("usedTokens").asLong()).isZero();

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

        // ---- BYOK 服务费：一次模型调用扣 1 积分（500 token 当量），quota 立即可见 ----
        assertThat(quotaService.chargeForByokCall(testUser.getId())).isTrue();
        JsonNode quota1 = json(mvc.perform(get("/api/user/quota")));
        assertThat(quota1.get("usedTokens").asLong()).isEqualTo(500L);
        assertThat(quota1.get("remainingTokens").asLong()).isEqualTo(49_500L);

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

    @Test
    void chatProvider_independentFromKeyMode_persistsAndEchoes() throws Exception {
        // ---- 默认 DEEPSEEK ----
        JsonNode initial = json(mvc.perform(get("/api/user/settings")));
        assertThat(initial.get("keyMode").asText()).isEqualTo("PLATFORM");
        assertThat(initial.get("chatProvider").asText()).isEqualTo("DEEPSEEK");

        // ---- 只传 chatProvider 即可切换，不要求 BYOK key；keyMode 保持不变 ----
        var switched = putJson("/api/user/settings", "{\"chatProvider\":\"QWEN\"}");
        assertThat(switched.andReturn().getResponse().getStatus()).isEqualTo(200);
        JsonNode body = json(switched);
        assertThat(body.get("chatProvider").asText()).isEqualTo("QWEN");
        assertThat(body.get("keyMode").asText()).isEqualTo("PLATFORM");

        JsonNode persisted = json(mvc.perform(get("/api/user/settings")));
        assertThat(persisted.get("chatProvider").asText()).isEqualTo("QWEN");
        assertThat(persisted.get("keyMode").asText()).isEqualTo("PLATFORM");

        // ---- 切回 DEEPSEEK ----
        assertThat(json(putJson("/api/user/settings", "{\"chatProvider\":\"DEEPSEEK\"}"))
                .get("chatProvider").asText()).isEqualTo("DEEPSEEK");

        // ---- 非法值 → 400 INVALID_CHAT_PROVIDER，且不改原值 ----
        var bad = putJson("/api/user/settings", "{\"chatProvider\":\"GROK\"}");
        assertThat(bad.andReturn().getResponse().getStatus()).isEqualTo(400);
        assertThat(json(bad).get("code").asText()).isEqualTo("INVALID_CHAT_PROVIDER");
        assertThat(json(mvc.perform(get("/api/user/settings")))
                .get("chatProvider").asText()).isEqualTo("DEEPSEEK");

        // ---- 两个字段都不传 → 400 INVALID_SETTINGS ----
        var empty = putJson("/api/user/settings", "{}");
        assertThat(empty.andReturn().getResponse().getStatus()).isEqualTo(400);
        assertThat(json(empty).get("code").asText()).isEqualTo("INVALID_SETTINGS");
    }

    @Test
    void chatProvider_canBeSwitchedUnderByokMode_qwenUsesPlatformKeyByDesign() throws Exception {
        // BYOK 用户也能选 QWEN（KeyResolver 对 qwen 恒返回平台 key）；两个字段分两次 PUT 互不阻塞。
        when(llm.verifyApiKey(anyString())).thenReturn(ApiKeyStatus.VALID);
        putJson("/api/user/api-key", "{\"apiKey\":\"" + SECRET + "\"}");
        putJson("/api/user/settings", "{\"keyMode\":\"BYOK\"}");

        JsonNode qwen = json(putJson("/api/user/settings", "{\"chatProvider\":\"QWEN\"}"));
        assertThat(qwen.get("keyMode").asText()).isEqualTo("BYOK");
        assertThat(qwen.get("chatProvider").asText()).isEqualTo("QWEN");
    }
}
