package com.bupt.mockinterview.web;

import com.bupt.mockinterview.domain.repository.JdProfileRepository;
import com.bupt.mockinterview.security.TenantContext;
import com.bupt.mockinterview.security.UserContext;
import com.bupt.mockinterview.security.JwtAuthFilter;
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
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 真正就绪后的 strict + JWT + OSIV 时序，无测试身份预填。 */
@SpringBootTest(properties = {
        "app.auth.jwt-enabled=true", "app.tenant.strict=true", "spring.jpa.open-in-view=true",
        "jwt.secret=01234567890123456789012345678901",
        "app.security.data-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
})
@AutoConfigureMockMvc(addFilters = false)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class StrictPublicRequestApiTest {
    private MockMvc mvc;
    @Autowired WebApplicationContext context;
    @Autowired JwtAuthFilter jwtFilter;
    @Autowired ObjectMapper mapper;
    @Autowired JdProfileRepository jdRepo;

    @BeforeEach
    void realRequestFiltersOnly() {
        // 排除全局测试基建的 TestUserContextFilter，否则其预填身份会掩盖生产问题。
        TenantContext.clear();
        UserContext.clear();
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(jwtFilter).build();
    }

    @AfterEach
    void clean() {
        TenantContext.clear();
        UserContext.clear();
    }

    @Test
    void osivFailsBeforeControllerWhenPublicEntryHasNoContext() throws Exception {
        MockMvc withoutEntryFilter = MockMvcBuilders.webAppContextSetup(context).build();
        withoutEntryFilter.perform(get("/")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("TENANT_CONTEXT_MISSING")));
        assertThat(TenantContext.getTenantId()).isNull();
    }

    @Test
    void anonymousPageAndHealthWorkWithoutLeakingTenant() throws Exception {
        mvc.perform(get("/")).andExpect(status().isOk());
        assertThat(TenantContext.getTenantId()).isNull();
        mvc.perform(get("/api/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
        assertThat(TenantContext.getTenantId()).isNull();
    }

    @Test
    void registrationLoginAndOwnerQuotaWorkAfterReady() throws Exception {
        String username = "strict" + UUID.randomUUID().toString().substring(0, 12);
        String body = mapper.writeValueAsString(Map.of("username", username, "password", "FixtureOnly_2026!"));
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
        assertThat(TenantContext.getTenantId()).isNull();
        String response = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String token = mapper.readTree(response).get("token").asText();
        mvc.perform(get("/api/user/quota").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalTokens").value(100000));
        assertThat(TenantContext.getTenantId()).isNull();
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("username", username, "password", "wrong"))))
                .andExpect(status().isUnauthorized());
        assertThat(TenantContext.getTenantId()).isNull();
    }

    @Test
    void protectedRequestsAndUnscopedPersistenceStillFailClosed() throws Exception {
        mvc.perform(get("/api/user/quota")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        assertThat(TenantContext.getTenantId()).isNull();
        assertThatThrownBy(() -> jdRepo.findAll()).hasStackTraceContaining("TENANT_CONTEXT_MISSING");
    }
}
