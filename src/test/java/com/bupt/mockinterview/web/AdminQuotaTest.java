package com.bupt.mockinterview.web;

import com.bupt.mockinterview.billing.QuotaService;
import com.bupt.mockinterview.billing.UsageScene;
import com.bupt.mockinterview.domain.KeyMode;
import com.bupt.mockinterview.domain.Quota;
import com.bupt.mockinterview.domain.User;
import com.bupt.mockinterview.domain.UserRole;
import com.bupt.mockinterview.domain.repository.QuotaRepository;
import com.bupt.mockinterview.domain.repository.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * R2 批 4 契约验收（api.md §7）：
 * ADMIN 充值 403/404/400/200 + 充值后 quota 视图；GET /api/user/usage 流水字段与 limit 收口。
 * 类级 @Transactional：角色提升/建用户随测试回滚，不污染共享 H2。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AdminQuotaTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper mapper;
    @Autowired
    private User testUser;
    @Autowired
    private UserRepository userRepo;
    @Autowired
    private QuotaRepository quotaRepo;
    @Autowired
    private QuotaService quotaService;
    @Autowired
    private PasswordEncoder encoder;

    private JsonNode json(org.springframework.test.web.servlet.ResultActions a) throws Exception {
        return mapper.readTree(a.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private void makeAdmin() {
        testUser.setRole(UserRole.ADMIN);
        userRepo.save(testUser);
    }

    private User createUserWithQuota(String username) {
        User u = userRepo.save(new User(username, encoder.encode("password")));
        quotaService.grantIfAbsent(u);
        return u;
    }

    @Test
    void nonAdmin_grant_returns403_forbidden() throws Exception {
        var actions = mvc.perform(post("/api/admin/quota")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"alice\",\"grantTokens\":200000}"));

        assertThat(actions.andReturn().getResponse().getStatus()).isEqualTo(403);
        assertThat(json(actions).get("code").asText()).isEqualTo("FORBIDDEN");
    }

    @Test
    void admin_grant_increasesTargetsQuota() throws Exception {
        makeAdmin();
        User alice = createUserWithQuota("alice_admin_grant");
        long before = quotaRepo.findByUserId(alice.getId()).orElseThrow().getTotalTokens();

        var actions = mvc.perform(post("/api/admin/quota")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"alice_admin_grant\",\"grantTokens\":200000}"));

        assertThat(actions.andReturn().getResponse().getStatus()).isEqualTo(200);
        JsonNode body = json(actions);
        assertThat(body.get("totalTokens").asLong()).isEqualTo(before + 200_000L);
        assertThat(body.get("remainingTokens").asLong())
                .isEqualTo(body.get("totalTokens").asLong() - body.get("usedTokens").asLong());

        Quota stored = quotaRepo.findByUserId(alice.getId()).orElseThrow();
        assertThat(stored.getTotalTokens()).isEqualTo(before + 200_000L);
    }

    @Test
    void admin_grant_unknownUser_returns404() throws Exception {
        makeAdmin();
        var actions = mvc.perform(post("/api/admin/quota")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"ghost-user\",\"grantTokens\":1}"));

        assertThat(actions.andReturn().getResponse().getStatus()).isEqualTo(404);
        assertThat(json(actions).get("code").asText()).isEqualTo("USER_NOT_FOUND");
    }

    @Test
    void admin_grantZeroOrNegative_returns400() throws Exception {
        makeAdmin();
        var zero = mvc.perform(post("/api/admin/quota")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"alice_admin_grant\",\"grantTokens\":0}"));
        assertThat(zero.andReturn().getResponse().getStatus()).isEqualTo(400);
        assertThat(json(zero).get("code").asText()).isEqualTo("BAD_REQUEST");

        var blank = mvc.perform(post("/api/admin/quota")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"\",\"grantTokens\":100}"));
        assertThat(blank.andReturn().getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void usageEndpoint_returnsOwnLogs_withContractFields_andLimits() throws Exception {
        // REQUIRES_NEW 独立提交：造一条本人 CHAT 流水（不随测试回滚，场景独立不影响其他测试断言）
        quotaService.settlePlatformCall(testUser.getId(), UsageScene.CHAT, "deepseek-flash",
                10L, 5L, 15L, false);

        var actions = mvc.perform(get("/api/user/usage").param("limit", "1"));
        assertThat(actions.andReturn().getResponse().getStatus()).isEqualTo(200);
        JsonNode arr = json(actions);
        assertThat(arr.isArray()).isTrue();
        assertThat(arr.size()).isEqualTo(1); // limit=1 收口生效
        JsonNode row = arr.get(0);
        assertThat(row.get("scene").asText()).isEqualTo("CHAT");
        assertThat(row.get("keyMode").asText()).isEqualTo(KeyMode.PLATFORM.name());
        assertThat(row.get("model").asText()).isEqualTo("deepseek-flash");
        assertThat(row.get("promptTokens").asLong()).isEqualTo(10L);
        assertThat(row.get("completionTokens").asLong()).isEqualTo(5L);
        assertThat(row.get("totalTokens").asLong()).isEqualTo(15L);
        assertThat(row.get("chargedTokens").asLong()).isEqualTo(15L);
        assertThat(row.get("createdAt").asText()).isNotBlank();
        assertThat(row.has("userId")).isFalse(); // 流水只回本人数据，不带内部 userId
    }

    @Test
    void usageEndpoint_limitOutOfRange_isClamped() throws Exception {
        var actions = mvc.perform(get("/api/user/usage").param("limit", "99999"));
        assertThat(actions.andReturn().getResponse().getStatus()).isEqualTo(200);
        JsonNode arr = json(actions);
        assertThat(arr.isArray()).isTrue();
        assertThat(arr.size()).isLessThanOrEqualTo(50);
    }
}
