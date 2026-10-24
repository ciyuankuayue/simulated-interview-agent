package com.bupt.mockinterview.web;

import com.bupt.mockinterview.billing.QuotaExhaustedException;
import com.bupt.mockinterview.llm.LlmException;
import com.bupt.mockinterview.llm.LlmPort;
import com.bupt.mockinterview.llm.LlmReply;
import com.bupt.mockinterview.security.TenantContext;
import com.bupt.mockinterview.security.UserContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "app.auth.jwt-enabled=true")
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class BuddyStreamApiTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired com.bupt.mockinterview.domain.User testUser;
    @MockBean LlmPort llm;

    private String token() throws Exception {
        String json = mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("username", "stream" + UUID.randomUUID().toString().substring(0, 10),
                        "password", "FixtureOnly_2026!"))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return mapper.readTree(json).path("token").asText();
    }

    @Test
    void requiresJwtAndValidatesBothInputsBeforeCallingModel() throws Exception {
        for (String mode : new String[]{"coach", "companion"}) {
            mvc.perform(post("/api/" + mode + "/stream").accept(MediaType.TEXT_EVENT_STREAM).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"message\":\"hello\"}")).andExpect(status().isUnauthorized());
            String token = token();
            mvc.perform(post("/api/" + mode + "/stream").header("Authorization", "Bearer " + token)
                    .accept(MediaType.TEXT_EVENT_STREAM)
                    .contentType(MediaType.APPLICATION_JSON).content("{\"message\":\" \"}"))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("BAD_REQUEST"));
        }
        verifyNoInteractions(llm);
    }

    @Test
    void deltasAndDoneUseOriginalAuthenticatedTenantThread() throws Exception {
        String token = token();
        when(llm.streamChat(any(), eq(false), anyList(), any())).thenAnswer(invocation -> {
            assertThat(UserContext.getUserId()).isNotNull().isEqualTo(TenantContext.getTenantId());
            Consumer<String> delta = invocation.getArgument(3);
            delta.accept("先讲清楚。"); delta.accept("再来一道短题。");
            return new LlmReply("先讲清楚。再来一道短题。", null);
        });
        String text = mvc.perform(post("/api/coach/stream").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"解释事务隔离\"}"))
                .andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(text).contains("event: delta", "event: done", "先讲清楚。", "再来一道短题。");
        // The outer test-only filter restores its fixture; the production filter clears inside it.
        assertThat(UserContext.getUserId()).isEqualTo(testUser.getId());
        assertThat(TenantContext.getTenantId()).isEqualTo(testUser.getId());
    }

    @Test
    void quotaBeforeFirstDeltaReturns402WhilePartialFailureGetsSseError() throws Exception {
        String token = token();
        when(llm.streamChat(any(), anyBoolean(), anyList(), any()))
                .thenThrow(new QuotaExhaustedException("额度不足"));
        mvc.perform(post("/api/coach/stream").header("Authorization", "Bearer " + token)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"hello\"}"))
                .andExpect(status().isPaymentRequired()).andExpect(jsonPath("$.code").value("QUOTA_EXHAUSTED"));
        doAnswer(invocation -> {
            Consumer<String> delta = invocation.getArgument(3);
            delta.accept("部分内容");
            throw new LlmException("连接断开");
        }).when(llm).streamChat(any(), anyBoolean(), anyList(), any());
        String text = mvc.perform(post("/api/coach/stream").header("Authorization", "Bearer " + token)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"hello\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(text).contains("event: delta", "event: error", "LLM_UNAVAILABLE").doesNotContain("event: done");
    }

    @Test
    void immediateSafetySupportSkipsModelAndKeepsExistingSafeShape() throws Exception {
        String token = token();
        String text = mvc.perform(post("/api/companion/stream").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"我现在准备自伤\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(text).contains("event: done", "\"safe\":true", "信任的人");
        verifyNoInteractions(llm);
    }
}
