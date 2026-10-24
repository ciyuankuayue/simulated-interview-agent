package com.bupt.mockinterview.web;

import com.bupt.mockinterview.domain.User;
import com.bupt.mockinterview.domain.repository.BuddyMemoryRepository;
import com.bupt.mockinterview.domain.repository.UserRepository;
import com.bupt.mockinterview.memory.BuddyMemoryService;
import com.bupt.mockinterview.memory.BuddyMemoryService.Candidate;
import com.bupt.mockinterview.security.JwtAuthFilter;
import com.bupt.mockinterview.security.JwtService;
import com.bupt.mockinterview.security.TenantContext;
import com.bupt.mockinterview.security.UserContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.orm.jpa.EntityManagerHolder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real JWT + strict tenant + short database transactions; no model calls or external services. */
@SpringBootTest(properties = {"app.auth.jwt-enabled=true", "app.tenant.strict=true",
        "jwt.secret=01234567890123456789012345678901",
        "app.security.data-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="})
@AutoConfigureMockMvc(addFilters = false)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class BuddyMemoryApiTest {
    @Autowired WebApplicationContext application;
    @Autowired JwtAuthFilter jwtFilter;
    @Autowired JwtService jwt;
    @Autowired ObjectMapper mapper;
    @Autowired BuddyMemoryService memory;
    @Autowired BuddyMemoryRepository repository;
    @Autowired UserRepository users;
    @Autowired UserLifecycleService lifecycle;
    @Autowired EntityManagerFactory entityManagerFactory;
    private MockMvc mvc;

    private record Owner(long id, String token) {}

    @BeforeEach
    void setup() {
        TenantContext.clear();
        UserContext.clear();
        mvc = MockMvcBuilders.webAppContextSetup(application).addFilters(jwtFilter).build();
    }

    @AfterEach
    void clearContext() {
        TenantContext.clear();
        UserContext.clear();
    }

    @Test
    void defaultsOffAndReadingOrDisablingNeverCreatesAProfile() throws Exception {
        Owner owner = register();
        getMemory(owner).andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.revision").value(0)).andExpect(jsonPath("$.entries").isEmpty());
        enabled(owner, false).andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(false));
        assertThat(as(owner, () -> repository.findByUserId(owner.id()))).isEmpty();
        assertThat(as(owner, () -> memory.context(owner.id(), "coach")).prompt()).isEmpty();
    }

    @Test
    void enabledAutomaticMemoriesAreBoundedAndScopedToEachBuddy() throws Exception {
        Owner owner = register();
        enabled(owner, true).andExpect(status().isOk()).andExpect(jsonPath("$.revision").value(1));
        apply(owner, "coach", List.of(new Candidate("target_role", "Java 后端", "目标是 Java 后端"),
                new Candidate("explanation_style", "先比喻再原理", "我喜欢先比喻再原理")));
        apply(owner, "companion", List.of(new Candidate("support_style", "先听我说，不急着建议", "我希望你先听我说，不急着建议")));
        String coach = as(owner, () -> memory.context(owner.id(), "coach")).prompt();
        String companion = as(owner, () -> memory.context(owner.id(), "companion")).prompt();
        assertThat(coach).contains("Java 后端", "先比喻再原理").doesNotContain("先听我说");
        assertThat(companion).contains("Java 后端", "先听我说").doesNotContain("先比喻再原理");
        getMemory(owner).andExpect(status().isOk()).andExpect(jsonPath("$.entries.length()").value(3))
                .andExpect(jsonPath("$.entries[0].scope").value("shared"))
                .andExpect(jsonPath("$.entries[0].source").value("AUTO"))
                .andExpect(jsonPath("$.entries[0].locked").value(false));
        String persisted = as(owner, () -> repository.findByUserId(owner.id()).orElseThrow().getStateJson());
        assertThat(persisted).doesNotContain("目标是", "我希望你", "evidence");
    }

    @Test
    void disabledMemoryRemainsViewableButStopsBothPromptAndAutomaticWrites() throws Exception {
        Owner owner = register();
        enabled(owner, true).andExpect(status().isOk());
        apply(owner, "coach", List.of(new Candidate("target_role", "后端", "我想做后端")));
        enabled(owner, false).andExpect(status().isOk()).andExpect(jsonPath("$.entries[0].value").value("后端"));
        assertThat(as(owner, () -> memory.context(owner.id(), "coach")).prompt()).isEmpty();
        apply(owner, "coach", List.of(new Candidate("target_role", "前端", "我改做前端")));
        getMemory(owner).andExpect(status().isOk()).andExpect(jsonPath("$.entries[0].value").value("后端"));
    }

    @Test
    void userOverrideLocksEntryAndDeletionPreventsAutomaticResurrection() throws Exception {
        Owner owner = register();
        enabled(owner, true).andExpect(status().isOk());
        edit(owner, "explanation_style", "先例子，再讲原理").andExpect(status().isOk())
                .andExpect(jsonPath("$.entries[0].source").value("USER"))
                .andExpect(jsonPath("$.entries[0].locked").value(true));
        apply(owner, "coach", List.of(new Candidate("explanation_style", "直接讲原理", "我喜欢直接讲原理")));
        getMemory(owner).andExpect(jsonPath("$.entries[0].value").value("先例子，再讲原理"));
        mvc.perform(delete("/api/buddy/memory/entries/explanation_style").header("Authorization", bearer(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.entries").isEmpty())
                .andExpect(jsonPath("$.blockedKeys[0]").value("explanation_style"));
        apply(owner, "coach", List.of(new Candidate("explanation_style", "直接讲原理", "我喜欢直接讲原理")));
        getMemory(owner).andExpect(jsonPath("$.entries").isEmpty());
        edit(owner, "explanation_style", "先用比喻").andExpect(status().isOk())
                .andExpect(jsonPath("$.entries[0].value").value("先用比喻"));
    }

    @Test
    void clearingBlocksEveryKeyAndDoesNotRestorePreferencesAfterReenabling() throws Exception {
        Owner owner = register();
        enabled(owner, true).andExpect(status().isOk());
        apply(owner, "coach", List.of(new Candidate("target_role", "后端", "我想做后端")));
        mvc.perform(delete("/api/buddy/memory/entries").header("Authorization", bearer(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.entries").isEmpty());
        enabled(owner, false).andExpect(status().isOk());
        enabled(owner, true).andExpect(status().isOk());
        apply(owner, "companion", List.of(new Candidate("target_role", "后端", "我想做后端"),
                new Candidate("support_style", "先倾听", "我希望你先倾听")));
        getMemory(owner).andExpect(jsonPath("$.entries").isEmpty()).andExpect(jsonPath("$.blockedKeys.length()").value(7));
        long beforeAllow = as(owner, () -> memory.context(owner.id(), "companion")).revision();
        mvc.perform(post("/api/buddy/memory/entries/support_style/allow").header("Authorization", bearer(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.revision").value(beforeAllow + 1))
                .andExpect(jsonPath("$.blockedKeys.length()").value(6));
        apply(owner, "companion", List.of(new Candidate("target_role", "后端", "我想做后端"),
                new Candidate("support_style", "先倾听", "我希望你先倾听")));
        getMemory(owner).andExpect(jsonPath("$.entries.length()").value(1))
                .andExpect(jsonPath("$.entries[0].key").value("support_style"));
    }

    @Test
    void lateResultsCannotUndoAnEditDeleteOrOffOnCycle() throws Exception {
        Owner owner = register();
        enabled(owner, true).andExpect(status().isOk());
        long beforeEdit = as(owner, () -> memory.context(owner.id(), "coach")).revision();
        edit(owner, "explanation_style", "从例子开始").andExpect(status().isOk());
        as(owner, () -> {
            memory.applyCandidates(owner.id(), "coach", beforeEdit, List.of(new Candidate("target_role", "后端", "我想做后端")));
            return null;
        });
        getMemory(owner).andExpect(jsonPath("$.entries.length()").value(1));
        long beforeToggle = as(owner, () -> memory.context(owner.id(), "coach")).revision();
        enabled(owner, false).andExpect(status().isOk());
        enabled(owner, true).andExpect(status().isOk());
        as(owner, () -> {
            memory.applyCandidates(owner.id(), "coach", beforeToggle, List.of(new Candidate("target_role", "前端", "我想做前端")));
            return null;
        });
        getMemory(owner).andExpect(jsonPath("$.entries.length()").value(1));
    }

    @Test
    void rejectsTemporaryDiagnosticWrongScopeAndUnboundedAutomaticData() throws Exception {
        Owner owner = register();
        enabled(owner, true).andExpect(status().isOk());
        apply(owner, "companion", List.of(new Candidate("support_style", "今天不复盘", "今天不复盘"),
                new Candidate("address_preference", "焦虑型人格", "我是焦虑型人格"),
                new Candidate("explanation_style", "多举例子", "希望多举例子")));
        apply(owner, "coach", List.of(new Candidate("mood", "开心", "很开心"),
                new Candidate("target_role", "x".repeat(301), "我想做后端"),
                new Candidate("available_time", "每周五小时", "")));
        getMemory(owner).andExpect(jsonPath("$.entries").isEmpty());
        apply(owner, "coach", List.of(new Candidate("target_role", "后端", "我想做后端"),
                new Candidate("preparation_stage", "基础复习", "正在基础复习"),
                new Candidate("available_time", "每周五小时", "每周五小时备考"),
                new Candidate("explanation_style", "先比喻", "我喜欢先比喻")));
        getMemory(owner).andExpect(jsonPath("$.entries.length()").value(3));
    }

    @Test
    void ownersCannotReadOrMutateEachOthersPreferencesAndUnknownFieldsAreRejected() throws Exception {
        Owner a = register(), b = register();
        enabled(a, true).andExpect(status().isOk());
        edit(a, "target_role", "A_PRIVATE").andExpect(status().isOk());
        getMemory(b).andExpect(jsonPath("$.enabled").value(false)).andExpect(jsonPath("$.entries").isEmpty());
        mvc.perform(patch("/api/buddy/memory").header("Authorization", bearer(b))
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(Map.of("enabled", true, "userId", a.id()))))
                .andExpect(status().isBadRequest());
        mvc.perform(patch("/api/buddy/memory/entries/target_role").header("Authorization", bearer(b))
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(Map.of("value", "B", "userId", a.id()))))
                .andExpect(status().isBadRequest());
        getMemory(a).andExpect(jsonPath("$.entries[0].value").value("A_PRIVATE"));
    }

    @Test
    void apiRequiresJwtAndStrictlyValidatesSettingsAndEntryValues() throws Exception {
        mvc.perform(get("/api/buddy/memory")).andExpect(status().isUnauthorized());
        mvc.perform(patch("/api/buddy/memory").contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":true}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(delete("/api/buddy/memory/entries")).andExpect(status().isUnauthorized());
        Owner owner = register();
        for (String body : List.of("{}", "null", "[]", "{\"enabled\":null}", "{\"enabled\":\"true\"}")) {
            mvc.perform(patch("/api/buddy/memory").header("Authorization", bearer(owner))
                    .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        }
        edit(owner, "target_role", "后端").andExpect(status().isBadRequest());
        enabled(owner, true).andExpect(status().isOk());
        edit(owner, "unknown", "后端").andExpect(status().isBadRequest());
        edit(owner, "target_role", " ").andExpect(status().isBadRequest());
        edit(owner, "target_role", "x".repeat(301)).andExpect(status().isBadRequest());
        edit(owner, "target_role", "x".repeat(300)).andExpect(status().isOk());
    }

    @Test
    void concurrentFirstEnableAndUserEditsSerializeWithoutDuplicateOrLostEntries() throws Exception {
        Owner owner = register();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch start = new CountDownLatch(1);
            Future<?> a = pool.submit(() -> { await(start); as(owner, () -> memory.setEnabled(owner.id(), true)); });
            Future<?> b = pool.submit(() -> { await(start); as(owner, () -> memory.setEnabled(owner.id(), true)); });
            start.countDown();
            a.get(20, TimeUnit.SECONDS);
            b.get(20, TimeUnit.SECONDS);
            assertThat(as(owner, () -> memory.view(owner.id())).revision()).isEqualTo(2);
            CountDownLatch editStart = new CountDownLatch(1);
            a = pool.submit(() -> { await(editStart); as(owner, () -> memory.edit(owner.id(), "target_role", "后端")); });
            b = pool.submit(() -> { await(editStart); as(owner, () -> memory.edit(owner.id(), "explanation_style", "先例子")); });
            editStart.countDown();
            a.get(20, TimeUnit.SECONDS);
            b.get(20, TimeUnit.SECONDS);
            assertThat(as(owner, () -> memory.view(owner.id())).entries()).hasSize(2);
            assertThat(as(owner, () -> memory.view(owner.id())).revision()).isEqualTo(4);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void lateModelResultRefreshesOsivCacheAfterConcurrentSettingsRequest() throws Exception {
        Owner owner = register();
        enabled(owner, true).andExpect(status().isOk());
        TenantContext.set(owner.id());
        var requestEntityManager = entityManagerFactory.createEntityManager();
        TransactionSynchronizationManager.bindResource(entityManagerFactory, new EntityManagerHolder(requestEntityManager));
        ExecutorService otherRequest = Executors.newSingleThreadExecutor();
        try {
            long snapshot = memory.context(owner.id(), "coach").revision();
            otherRequest.submit(() -> as(owner, () -> memory.setEnabled(owner.id(), false))).get(20, TimeUnit.SECONDS);
            otherRequest.submit(() -> as(owner, () -> memory.setEnabled(owner.id(), true))).get(20, TimeUnit.SECONDS);
            memory.applyCandidates(owner.id(), "coach", snapshot,
                    List.of(new Candidate("target_role", "后端", "我想做后端")));
            assertThat(memory.view(owner.id()).entries()).isEmpty();
            assertThat(memory.view(owner.id()).revision()).isEqualTo(snapshot + 2);
        } finally {
            otherRequest.shutdownNow();
            TransactionSynchronizationManager.unbindResource(entityManagerFactory);
            requestEntityManager.close();
            TenantContext.clear();
        }
    }

    @Test
    void deletingOwnerCascadesStoredPreferences() {
        // A user created directly has no unrelated quota FK, isolating the new FK behavior.
        TenantContext.set(0L);
        User owner = users.save(new User("mem" + UUID.randomUUID().toString().substring(0, 12), "fixture-hash"));
        TenantContext.set(owner.getId());
        memory.setEnabled(owner.getId(), true);
        memory.edit(owner.getId(), "target_role", "后端");
        lifecycle.deleteUser(owner);
        assertThat(repository.findByUserId(owner.getId())).isEmpty();
    }

    private Owner register() throws Exception {
        String body = mapper.writeValueAsString(Map.of("username", "mem" + UUID.randomUUID().toString().substring(0, 12),
                "password", "FixtureOnly_2026!"));
        String content = mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode response = mapper.readTree(content);
        String token = response.get("token").asText();
        return new Owner(JwtService.userIdFrom(jwt.parseToken(token)), token);
    }

    private ResultActions getMemory(Owner owner) throws Exception {
        return mvc.perform(get("/api/buddy/memory").header("Authorization", bearer(owner)));
    }

    private ResultActions enabled(Owner owner, boolean enabled) throws Exception {
        return mvc.perform(patch("/api/buddy/memory").header("Authorization", bearer(owner))
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(Map.of("enabled", enabled))));
    }

    private ResultActions edit(Owner owner, String key, String value) throws Exception {
        return mvc.perform(patch("/api/buddy/memory/entries/" + key).header("Authorization", bearer(owner))
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(Map.of("value", value))));
    }

    private void apply(Owner owner, String mode, List<Candidate> candidates) {
        as(owner, () -> {
            memory.applyCandidates(owner.id(), mode, memory.context(owner.id(), mode).revision(), candidates);
            return null;
        });
    }

    private static <T> T as(Owner owner, Supplier<T> action) {
        TenantContext.set(owner.id());
        try {
            return action.get();
        } finally {
            TenantContext.clear();
        }
    }

    private static String bearer(Owner owner) {
        return "Bearer " + owner.token();
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("test barrier timed out");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
