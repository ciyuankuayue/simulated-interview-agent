package com.bupt.mockinterview.web;

import com.bupt.mockinterview.billing.QuotaExhaustedException;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * R1-3 回归：BYOK 点数耗尽（402）必须穿透「JD 解析失败降级为空 keywords」的边界，
 * 直接以 HTTP 402 QUOTA_EXHAUSTED 返回——而不是 200 + keywords=[] 把用户的调用静默吞掉。
 * （该 bug 曾在 bonus=0 的端到端验证中真实出现。）
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class JdQuotaPropagationTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper mapper;

    @MockBean
    private LlmPort llm;

    @Test
    void quotaExhaustedDuringJdParse_returns402_insteadOfEmptyKeywords() throws Exception {
        // MaterialController 走带 scene 的 7 参重载（Mockito mock 不会走 interface default 委托，必须桩这个签名）
        when(llm.chat(anyString(), anyBoolean(), anyList(), any(), anyBoolean(),
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.any(com.bupt.mockinterview.billing.UsageScene.class)))
                .thenThrow(new QuotaExhaustedException(
                        "服务点数已用完：删除你的 Key 切回平台模式，或联系管理员充值"));

        var actions = mvc.perform(post("/api/jd")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"rawText\":\"招聘 Java 工程师，熟悉 Spring Boot。\"}"));

        assertThat(actions.andReturn().getResponse().getStatus()).isEqualTo(402);
        JsonNode body = mapper.readTree(
                actions.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(body.get("code").asText()).isEqualTo("QUOTA_EXHAUSTED");
        assertThat(body.get("message").asText()).contains("切回平台");
    }
}
