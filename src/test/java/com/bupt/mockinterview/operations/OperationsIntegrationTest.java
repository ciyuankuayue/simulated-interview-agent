package com.bupt.mockinterview.operations;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.bupt.mockinterview.domain.*;
import com.bupt.mockinterview.domain.repository.UserRepository;
import com.bupt.mockinterview.security.*;
import com.fasterxml.jackson.databind.*;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.*;

import java.nio.charset.StandardCharsets;
import java.util.*;

@SpringBootTest(properties = {"app.auth.jwt-enabled=true", "app.launch.telemetry-enabled=true"})
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OperationsIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired UserRepository users;
    @Autowired ProductEventService events;
    @Autowired OperationsTransactions tx;
    @Autowired OperationsRetention retention;
    @jakarta.persistence.PersistenceContext jakarta.persistence.EntityManager em;
    String admin, alice, bob, name;
    Long uid;

    JsonNode json(ResultActions r) throws Exception {
        return mapper.readTree(
                r.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    String register(String n) throws Exception {
        return json(mvc.perform(
                                post("/api/auth/register")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                mapper.writeValueAsString(
                                                        Map.of(
                                                                "username",
                                                                n,
                                                                "password",
                                                                "secret123"))))
                        .andExpect(status().isCreated()))
                .get("token")
                .asText();
    }

    ResultActions read(String t, String path) throws Exception {
        return mvc.perform(get(path).header("Authorization", "Bearer " + t));
    }

    ResultActions send(String t, String path, Object body) throws Exception {
        return mvc.perform(
                post(path)
                        .header("Authorization", "Bearer " + t)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(body)));
    }

    ResultActions update(String t, String path, Object body) throws Exception {
        return mvc.perform(
                put(path)
                        .header("Authorization", "Bearer " + t)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(body)));
    }

    @BeforeEach
    void setup() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        name = "ops-a-" + suffix;
        alice = register(name);
        bob = register("ops-b-" + suffix);
        String an = "ops-admin-" + suffix;
        admin = register(an);
        User a = users.findByUsername(an).orElseThrow();
        a.setRole(UserRole.ADMIN);
        users.saveAndFlush(a);
        uid = users.findByUsername(name).orElseThrow().getId();
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
        UserContext.clear();
    }

    Map<String, Object> survey() {
        return Map.of(
                "progress",
                "START_FAILED",
                "experience",
                "点开始后一直等待，没能看到第一题",
                "blockers",
                List.of("SPEED", "ERROR"),
                "suggestions",
                "希望失败后可以恢复",
                "followUp",
                false,
                "contact",
                "");
    }

    @Test
    void adminAuthorizationAndOwnVisibility() throws Exception {
        read(alice, "/api/admin/users").andExpect(status().isForbidden());
        read(alice, "/api/admin/overview").andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/users")).andExpect(status().isUnauthorized());
        read(admin, "/api/admin/users?q=" + name)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].username").value(name));
        assertThat(json(read(bob, "/api/user/grants")).get("items")).isEmpty();
    }

    @Test
    void grantIdempotencyAndLedgerAreAtomic() throws Exception {
        long before = json(read(alice, "/api/user/quota")).get("totalTokens").asLong();
        var req =
                Map.of(
                        "username",
                        name,
                        "points",
                        200,
                        "type",
                        "GIFT",
                        "reason",
                        "内测赠送",
                        "requestId",
                        UUID.randomUUID().toString());
        send(admin, "/api/admin/grants", req).andExpect(status().isOk());
        send(admin, "/api/admin/grants", req).andExpect(status().isOk());
        assertThat(json(read(alice, "/api/user/quota")).get("totalTokens").asLong())
                .isEqualTo(before + 100000);
        var changed = new HashMap<String, Object>(req);
        changed.put("points", 201);
        send(admin, "/api/admin/grants", changed).andExpect(status().isConflict());
        read(alice, "/api/user/grants")
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].points").value(200));
        read(bob, "/api/user/grants").andExpect(jsonPath("$.total").value(0));
    }

    @Test
    void paidRequiresMoneyAndGiftCannotBeIncome() throws Exception {
        var req =
                new HashMap<String, Object>(
                        Map.of(
                                "username",
                                name,
                                "points",
                                200,
                                "type",
                                "PAID",
                                "reason",
                                "充值",
                                "requestId",
                                UUID.randomUUID().toString()));
        send(admin, "/api/admin/grants", req).andExpect(status().isBadRequest());
        req.put("amountCny", 9.9);
        req.put("orderRef", "manual-test-1");
        send(admin, "/api/admin/grants", req).andExpect(status().isOk());
        req.put("type", "GIFT");
        req.put("requestId", UUID.randomUUID().toString());
        send(admin, "/api/admin/grants", req).andExpect(status().isBadRequest());
    }

    @Test
    void feedbackSupplementAndRewardExactlyOnce() throws Exception {
        long before = json(read(alice, "/api/user/quota")).get("totalTokens").asLong();
        long id =
                json(send(alice, "/api/feedback", survey()).andExpect(status().isOk()))
                        .get("id")
                        .asLong();
        send(alice, "/api/feedback", survey()).andExpect(status().isConflict());
        read(bob, "/api/feedback").andExpect(jsonPath("$.feedback").isEmpty());
        send(
                        admin,
                        "/api/admin/feedback/" + id + "/review",
                        Map.of("action", "NEEDS_INFO", "note", "请说明当时等待多久"))
                .andExpect(status().isOk());
        update(alice, "/api/feedback", survey())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"));
        send(
                        admin,
                        "/api/admin/feedback/" + id + "/review",
                        Map.of("action", "REWARD", "note", "收到，会排查"))
                .andExpect(status().isOk());
        send(admin, "/api/admin/feedback/" + id + "/review", Map.of("action", "REWARD"))
                .andExpect(status().isOk());
        assertThat(json(read(alice, "/api/user/quota")).get("totalTokens").asLong())
                .isEqualTo(before + 100000);
        update(alice, "/api/feedback", survey()).andExpect(status().isConflict());
        read(alice, "/api/user/grants").andExpect(jsonPath("$.total").value(1));
        send(bob, "/api/admin/feedback/" + id + "/review", Map.of("action", "REWARD"))
                .andExpect(status().isForbidden());
    }

    @Test
    void invalidExclusiveAndBlankFeedbackRejected() throws Exception {
        var req = new HashMap<String, Object>(survey());
        req.put("blockers", List.of("NONE", "COST"));
        send(alice, "/api/feedback", req).andExpect(status().isBadRequest());
        req.put("blockers", List.of("UNSURE", "ERROR"));
        send(alice, "/api/feedback", req).andExpect(status().isBadRequest());
        req.put("blockers", List.of("ERROR"));
        req.put("experience", "   ");
        send(alice, "/api/feedback", req).andExpect(status().isBadRequest());
    }

    @Test
    void telemetryOptOutAndUnknownPropertiesRejected() throws Exception {
        var event =
                Map.of(
                        "eventId",
                        UUID.randomUUID().toString(),
                        "name",
                        "PAGE_VIEW",
                        "page",
                        "prepare",
                        "device",
                        "DESKTOP");
        send(alice, "/api/product-events/batch", Map.of("events", List.of(event, event)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(1));
        update(
                        alice,
                        "/api/user/analytics-preference",
                        Map.of("enabled", false, "reportInvitationSeen", true))
                .andExpect(status().isOk());
        send(alice, "/api/product-events/batch", Map.of("events", List.of(event)))
                .andExpect(jsonPath("$.accepted").value(0));
        read(alice, "/api/user/analytics-preference")
                .andExpect(jsonPath("$.reportInvitationSeen").value(true));
        var unknown = new HashMap<String, Object>(event);
        unknown.put("prompt", "private text");
        send(alice, "/api/product-events/batch", Map.of("events", List.of(unknown)))
                .andExpect(status().isBadRequest());
        var spoof = new HashMap<String, Object>(event);
        spoof.put("name", "INTERVIEW_STARTED");
        send(alice, "/api/product-events/batch", Map.of("events", List.of(spoof)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void serverRecorderRestoresContextAndAdminQueriesWork() throws Exception {
        TenantContext.set(0L);
        events.recordServer(
                uid, "MODEL_FAILED", "failure:" + UUID.randomUUID(), null, "interview", "TIMEOUT");
        assertThat(TenantContext.getTenantId()).isZero();
        read(admin, "/api/admin/users/" + uid + "/activity")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.id").value(uid));
        read(admin, "/api/admin/overview?days=7").andExpect(status().isOk());
        read(admin, "/api/admin/analytics?days=7")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cost.estimated").value(true));
        read(admin, "/api/admin/feedback").andExpect(status().isOk());
        read(admin, "/api/admin/grants").andExpect(status().isOk());
        read(admin, "/api/admin/overview?days=91").andExpect(status().isBadRequest());
    }

    @Test
    void nullEventAndRegisterTenantEvidence() throws Exception {
        send(
                        alice,
                        "/api/product-events/batch",
                        Map.of("events", java.util.Arrays.asList((Object) null)))
                .andExpect(status().isBadRequest());
        JsonNode activity =
                json(
                        read(admin, "/api/admin/users/" + uid + "/activity")
                                .andExpect(status().isOk()));
        boolean registered = false;
        for (JsonNode e : activity.get("events"))
            if ("REGISTERED".equals(e.get("name").asText())) registered = true;
        assertThat(registered).isTrue();
    }

    @Test
    void analyticsSeparatesKnownCostUnknownFailureAndRetries() throws Exception {
        String model = "measurement-" + uid;
        send(alice, "/api/product-events/batch", Map.of("events", List.of(
                Map.of("eventId", UUID.randomUUID().toString(), "name", "FEEDBACK_OPEN", "page", "feedback", "source", "account"),
                Map.of("eventId", UUID.randomUUID().toString(), "name", "PAGE_VIEW", "page", "prepare", "source", "launch_demo"))))
                .andExpect(status().isOk());
        tx.tenant(uid, () -> {
            LlmUsageLog known = new LlmUsageLog(uid, com.bupt.mockinterview.billing.UsageScene.CHAT,
                    KeyMode.PLATFORM, model, 800, 200, 1000, 1000, false);
            known.setPlatformCostMicros(3200L); known.setStatus("SUCCESS"); known.setAttemptCount(2);
            known.setSessionId(987654321L); // Failed/rolled-back start has usage but no completed report.
            em.persist(known);
            LlmUsageLog failed = new LlmUsageLog(uid, com.bupt.mockinterview.billing.UsageScene.CHAT,
                    KeyMode.PLATFORM, model, 0, 0, 0, 0, true);
            failed.setStatus("FAILED"); failed.setAttemptCount(3); em.persist(failed);
            return null;
        });
        JsonNode result = json(read(admin, "/api/admin/analytics?days=7").andExpect(status().isOk()));
        JsonNode group = null;
        for (JsonNode row : result.get("models")) if (model.equals(row.get("model").asText())) group = row;
        assertThat(group).isNotNull();
        assertThat(group.get("calls").asInt()).isEqualTo(2);
        assertThat(group.get("retryAttempts").asInt()).isEqualTo(3);
        assertThat(group.get("failedCalls").asInt()).isEqualTo(1);
        assertThat(group.get("unknownPlatformCostCalls").asInt()).isEqualTo(1);
        assertThat(group.get("platformEstimatedCostCny").decimalValue()).isEqualByComparingTo("0.0032");
        assertThat(result.get("scenes").isEmpty()).isFalse();
        assertThat(result.get("daily").get(0).has("platformEstimatedCostCny")).isTrue();
        assertThat(result.get("cost").get("sessionCount").asInt()).isZero();
        assertThat(result.get("cost").get("sessionMedianCny").isNull()).isTrue();
        var sources = new ArrayList<String>();
        result.get("sources").forEach(row -> sources.add(row.get("source").asText()));
        assertThat(sources).contains("launch_demo").doesNotContain("account");
    }

    @Test
    void retentionClearsPrivateFieldsAndPreservesRewardMarker() throws Exception {
        var req = new HashMap<String, Object>(survey());
        req.put("followUp", true);
        req.put("contact", "test@example.invalid");
        long id =
                json(send(alice, "/api/feedback", req).andExpect(status().isOk()))
                        .get("id")
                        .asLong();
        send(admin, "/api/admin/feedback/" + id + "/review", Map.of("action", "REWARD"))
                .andExpect(status().isOk());
        tx.tenant(
                uid,
                () -> {
                    em.createQuery(
                                    "update ProductFeedback f set"
                                        + " f.updatedAt=:old,f.contactUpdatedAt=:old where"
                                        + " f.userId=:u")
                            .setParameter("u", uid)
                            .setParameter(
                                    "old",
                                    java.time.Instant.now()
                                            .minus(181, java.time.temporal.ChronoUnit.DAYS))
                            .executeUpdate();
                    return null;
                });
        retention.purge();
        read(alice, "/api/feedback")
                .andExpect(jsonPath("$.feedback.contact").isEmpty())
                .andExpect(jsonPath("$.feedback.status").value("REWARDED"))
                .andExpect(jsonPath("$.feedback.experience").value("[已按保留期限清理]"));
        read(alice, "/api/user/grants").andExpect(jsonPath("$.total").value(1));
    }
}
