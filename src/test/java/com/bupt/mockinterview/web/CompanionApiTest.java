package com.bupt.mockinterview.web;

import com.bupt.mockinterview.billing.QuotaExhaustedException;
import com.bupt.mockinterview.domain.KeyMode;
import com.bupt.mockinterview.llm.LlmPort;
import com.bupt.mockinterview.llm.LlmReply;
import com.bupt.mockinterview.llm.chat.ModelRole;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "app.auth.jwt-enabled=true")
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CompanionApiTest {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @MockBean private LlmPort llm;

    @Test
    void companionEndpointsStillRequireAuthentication() throws Exception {
        mvc.perform(post("/api/companion/chat").contentType(MediaType.APPLICATION_JSON)
                .content("{\"message\":\"hello\"}")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/companion/reset")).andExpect(status().isUnauthorized());
        verifyNoInteractions(llm);
    }

    @Test
    void bothQuotaModesExposeBetaContactAndEmptyReplyStaysRetryable() throws Exception {
        var registration = mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("username", "warm" + UUID.randomUUID().toString().substring(0, 12),
                        "password", "FixtureOnly_2026!"))))
                .andExpect(status().isCreated()).andReturn().getResponse();
        String token = mapper.readTree(registration.getContentAsString()).get("token").asText();
        when(llm.chat(eq(ModelRole.FAST), eq(false), anyList(), isNull(), eq(false)))
                .thenThrow(QuotaExhaustedException.forMode(KeyMode.PLATFORM))
                .thenThrow(QuotaExhaustedException.forMode(KeyMode.BYOK))
                .thenReturn(new LlmReply(" ", null), new LlmReply("接着你刚才的话说。", null));
        for (int i = 0; i < 2; i++) {
            mvc.perform(post("/api/companion/chat").header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"hello\"}"))
                    .andExpect(status().isPaymentRequired()).andExpect(jsonPath("$.code").value("QUOTA_EXHAUSTED"))
                    .andExpect(jsonPath("$.message", containsString("内测阶段")))
                    .andExpect(jsonPath("$.message", containsString("13481264947")));
        }
        mvc.perform(post("/api/companion/chat").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"hello\"}"))
                .andExpect(status().isGatewayTimeout()).andExpect(jsonPath("$.code").value("LLM_UNAVAILABLE"));
        mvc.perform(post("/api/companion/chat").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"hello\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.safe").value(false));
    }
}
