package com.bupt.mockinterview.web;

import com.bupt.mockinterview.billing.QuotaExhaustedException;
import com.bupt.mockinterview.llm.LlmException;
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
import org.springframework.test.web.servlet.ResultActions;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "app.auth.jwt-enabled=true")
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class StudyCoachApiTest {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @MockBean private LlmPort llm;

    private String register() throws Exception {
        var response = mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("username", "coach" + UUID.randomUUID().toString().substring(0, 12),
                        "password", "FixtureOnly_2026!"))))
                .andExpect(status().isCreated()).andReturn().getResponse();
        return mapper.readTree(response.getContentAsString(StandardCharsets.UTF_8)).get("token").asText();
    }
    private ResultActions chat(String token, String message) throws Exception {
        return mvc.perform(post("/api/coach/chat").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(Map.of("message", message))));
    }

    @Test
    void bothEndpointsRequireJwt() throws Exception {
        mvc.perform(post("/api/coach/chat").contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"question\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/coach/reset")).andExpect(status().isUnauthorized());
        verifyNoInteractions(llm);
    }

    @Test
    void rejectsBlankAndOversizedInputsBeforeModelCall() throws Exception {
        String token = register();
        chat(token, " ").andExpect(status().isBadRequest());
        chat(token, "x".repeat(6001)).andExpect(status().isBadRequest());
        verifyNoInteractions(llm);
    }

    @Test
    void successfulChatAndResetOnlyUseAuthenticatedOwner() throws Exception {
        String a = register(), b = register();
        when(llm.chat(eq(ModelRole.FAST), eq(false), anyList(), isNull(), eq(false)))
                .thenAnswer(invocation -> {
                    String input = invocation.getArgument(2).toString();
                    if (input.contains("B-question")) assertThat(input).doesNotContain("A_PRIVATE");
                    if (input.contains("after-reset")) assertThat(input).doesNotContain("A_PRIVATE");
                    return new LlmReply("小师的回答", null);
                });
        chat(a, "A_PRIVATE").andExpect(status().isOk()).andExpect(jsonPath("$.reply").value("小师的回答"));
        chat(b, "B-question").andExpect(status().isOk());
        mvc.perform(post("/api/coach/reset").header("Authorization", "Bearer " + a))
                .andExpect(status().isOk()).andExpect(jsonPath("$.ok").value(true));
        chat(a, "after-reset").andExpect(status().isOk());
    }

    @Test
    void modelAndQuotaErrorsRetainExistingHttpSemantics() throws Exception {
        String token = register();
        when(llm.chat(eq(ModelRole.FAST), eq(false), anyList(), isNull(), eq(false)))
                .thenThrow(new QuotaExhaustedException("额度不足"))
                .thenThrow(new IllegalStateException("Key 未配置"))
                .thenThrow(new LlmException("模型暂不可用"));
        chat(token, "question").andExpect(status().isPaymentRequired()).andExpect(jsonPath("$.code").value("QUOTA_EXHAUSTED"));
        chat(token, "question").andExpect(status().isConflict());
        chat(token, "question").andExpect(status().isGatewayTimeout());
    }
}
