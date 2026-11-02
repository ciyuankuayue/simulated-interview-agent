package com.bupt.mockinterview.launch;

import com.bupt.mockinterview.MockInterviewApplication;
import com.bupt.mockinterview.billing.*;
import com.bupt.mockinterview.security.*;
import com.fasterxml.jackson.databind.*;
import okhttp3.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.DriverManager;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

/** Opt-in only; the companion script creates disposable containers on separate loopback ports. */
@EnabledIfEnvironmentVariable(named = "LAUNCH_MYSQL_VERIFY", matches = "true")
class LaunchMysqlVerificationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final OkHttpClient HTTP = new OkHttpClient.Builder().callTimeout(45, TimeUnit.SECONDS).build();
    private final String password = Objects.requireNonNull(System.getenv("LAUNCH_MYSQL_PASSWORD"));
    private final String schema = "launch_verify_" + UUID.randomUUID().toString().replace("-", "");
    private final String jdbc = "jdbc:mysql://127.0.0.1:13317/" + schema
            + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai&characterEncoding=UTF-8";
    private ServletWebServerApplicationContext app;
    private String base;

    @Test void existingV6UpgradePreservesHistoryAndRunsProductionFlows() throws Exception { verify(true); }
    @Test void freshHibernateBootstrapThenProductionFlywayIsRepeatable() throws Exception { verify(false); }

    private void verify(boolean old) throws Exception {
        try (var c = DriverManager.getConnection("jdbc:mysql://127.0.0.1:13317/?useSSL=false&allowPublicKeyRetrieval=true", "root", password)) {
            c.createStatement().execute("CREATE DATABASE `" + schema + "` CHARACTER SET utf8mb4");
        }
        try {
            start(false);
            var db = app.getBean(JdbcTemplate.class);
            var legacy = register("legacy");
            long legacyId = legacy.get("userId").asLong();
            db.update("update quota set total_tokens=50000,used_tokens=1320 where user_id=?", legacyId);
            db.update("insert into llm_usage_log (tenant_id,user_id,scene,key_mode,model,prompt_tokens,completion_tokens,total_tokens,charged_tokens,estimated,created_at) values (?,?,'CHAT','PLATFORM','deepseek-flash',10,5,15,15,false,NOW(6))", legacyId, legacyId);
            if (old) {
                for (String table : List.of("operation_grant", "product_feedback", "product_event", "analytics_preference", "platform_budget_reservation", "platform_budget_day")) db.execute("DROP TABLE " + table);
                db.execute("ALTER TABLE llm_usage_log DROP INDEX uk_usage_user_call");
                for (String column : List.of("call_id", "session_id", "due_tokens", "billing_version", "platform_cost_micros", "pricing_version", "status", "attempt_count"))
                    db.execute("ALTER TABLE llm_usage_log DROP COLUMN " + column);
                Flyway.configure().dataSource(jdbc, "root", password).baselineVersion("6").load().baseline();
            }
            app.close(); app = null;
            start(true); // prod + Flyway + strict tenant + schema validation, never ddl-auto:update
            db = app.getBean(JdbcTemplate.class);
            assertThat(db.queryForObject("select count(*) from flyway_schema_history where version in ('7','8') and success=1", Integer.class)).isEqualTo(2);
            assertThat(db.queryForObject("select total_tokens from quota where user_id=?", Long.class, legacyId)).isEqualTo(50000);
            assertThat(db.queryForObject("select used_tokens from quota where user_id=?", Long.class, legacyId)).isEqualTo(1320);
            assertThat(db.queryForObject("select count(*) from llm_usage_log where user_id=? and charged_tokens=15 and call_id is null", Integer.class, legacyId)).isEqualTo(1);
            assertThat(app.getBean(Flyway.class).migrate().migrationsExecuted).isZero();
            productionFlows();
        } finally {
            if (app != null) app.close();
            TenantContext.clear(); UserContext.clear(); UsageContext.clear();
        }
    }

    private void start(boolean migrate) {
        TenantContext.clear(); UserContext.clear();
        app = (ServletWebServerApplicationContext) new SpringApplicationBuilder(MockInterviewApplication.class).run(
                "--spring.profiles.active=prod", "--server.address=127.0.0.1", "--server.port=0",
                "--spring.datasource.url=" + jdbc, "--spring.datasource.username=root", "--spring.datasource.password=" + password,
                "--spring.jpa.hibernate.ddl-auto=" + (migrate ? "validate" : "update"), "--spring.flyway.enabled=" + migrate,
                "--spring.data.redis.host=127.0.0.1", "--spring.data.redis.port=16387", "--spring.data.redis.timeout=1s",
                "--app.auth.jwt-enabled=true", "--app.tenant.strict=true", "--app.launch.telemetry-enabled=true",
                "--app.launch.limits.enabled=true", "--app.rag.ram-directory=true", "--app.rag.clean-on-empty-sources=false",
                "--app.platform-budget.daily-cny=100", "--app.security.bootstrap-admin.username=verifier",
                "--app.security.bootstrap-admin.password=" + password, "--deepseek.api-key=", "--dashscope.api-key=",
                "--jwt.secret=launch-local-test-jwt-secret-at-least-32-bytes-0000",
                "--app.security.data-key=MTIzNDU2Nzg5MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTI=");
        base = "http://127.0.0.1:" + app.getWebServer().getPort();
    }

    private JsonNode register(String name) throws Exception { return request("/api/auth/register", Map.of("username", name, "password", password), null, 201); }

    private JsonNode request(String path, Object body, String token, int expected) throws Exception {
        var builder = new Request.Builder().url(base + path);
        if (token != null) builder.header("Authorization", "Bearer " + token);
        if (body != null) builder.post(RequestBody.create(JSON.writeValueAsString(body), MediaType.get("application/json")));
        try (var response = HTTP.newCall(builder.build()).execute()) {
            JsonNode result = JSON.readTree(response.body().string());
            assertThat(response.code()).as("%s code=%s", path, result.path("code").asText()).isEqualTo(expected);
            return result;
        }
    }

    private void productionFlows() throws Exception {
        String admin = request("/api/auth/login", Map.of("username", "verifier", "password", password), null, 200).get("token").asText();
        var alice = register("alice"); var bob = register("bobby");
        String a = alice.get("token").asText(), b = bob.get("token").asText();
        long uid = alice.get("userId").asLong();
        assertThat(alice.get("role").asText()).isEqualTo("USER");
        request("/api/admin/users", null, a, 403);
        request("/api/admin/users", null, null, 401);
        var grant = Map.of("username", "alice", "points", 200, "type", "GIFT", "reason", "isolated verification", "requestId", UUID.randomUUID().toString());
        concurrent(8, () -> request("/api/admin/grants", grant, admin, 200));
        assertThat(request("/api/user/quota", null, a, 200).get("totalTokens").asLong()).isEqualTo(200000);
        assertThat(request("/api/user/grants", null, a, 200).get("total").asInt()).isEqualTo(1);
        assertThat(request("/api/user/grants", null, b, 200).get("total").asInt()).isZero();
        var feedback = request("/api/feedback", Map.of("progress", "START_FAILED", "experience", "合成迁移验收：在开始页等待首题", "blockers", List.of("SPEED", "ERROR"), "suggestions", "希望失败后可恢复", "followUp", false, "contact", ""), a, 200);
        assertThat(request("/api/feedback", null, b, 200).get("feedback").isNull()).isTrue();
        String review = "/api/admin/feedback/" + feedback.get("id").asLong() + "/review";
        concurrent(6, () -> request(review, Map.of("action", "REWARD"), admin, 200));
        assertThat(request("/api/user/quota", null, a, 200).get("totalTokens").asLong()).isEqualTo(300000);
        assertThat(request("/api/user/grants", null, a, 200).get("total").asInt()).isEqualTo(2);

        var quota = app.getBean(QuotaService.class);
        concurrent(8, () -> {
            TenantContext.set(uid);
            try (var call = UsageContext.withCall("same-model-call")) {
                quota.settlePlatformCall(uid, UsageScene.CHAT, "deepseek:deepseek-flash", 800, 200, 1000, false);
            } finally { TenantContext.clear(); }
            return null;
        });
        TenantContext.set(uid);
        try { quota.recordByokChat(uid, UsageScene.CHAT, "deepseek:deepseek-flash", 8000, 2000, 10000, false); }
        finally { TenantContext.clear(); }
        assertThat(request("/api/user/quota", null, a, 200).get("usedTokens").asLong()).isEqualTo(2000);
        assertThat(request("/api/user/usage", null, a, 200)).hasSize(2);
        assertThat(request("/api/user/usage", null, b, 200)).isEmpty();
        request("/api/admin/overview", null, admin, 200);
        request("/api/admin/analytics", null, admin, 200);
        TenantContext.set(uid);
        try { budgetAndRedis(uid); } finally { TenantContext.clear(); }
    }

    private void budgetAndRedis(long uid) throws Exception {
        var budget = app.getBean(PlatformBudgetService.class);
        var reservations = new ConcurrentLinkedQueue<PlatformBudgetService.Reservation>();
        concurrent(8, () -> {
            TenantContext.set(uid);
            // 20.032768 CNY per reservation: exactly four fit within the 100 CNY budget.
            try { reservations.add(budget.reserve(UUID.randomUUID().toString(), "deepseek:deepseek-flash", 10000000, 4096, 1)); }
            catch (PlatformBudgetExceededException expected) { }
            finally { TenantContext.clear(); }
            return null;
        });
        assertThat(reservations).hasSize(4);
        var db = app.getBean(JdbcTemplate.class);
        assertThat(db.queryForObject("select reserved_micros+spent_micros from platform_budget_day where budget_day=?", Long.class,
                LocalDate.now(ZoneId.of("Asia/Shanghai")))).isLessThanOrEqualTo(100000000L);
        for (var r : reservations) { budget.settle(r, 1000L, 1); budget.settle(r, 1000L, 1); }
        assertThat(db.queryForObject("select sum(reserved_micros) from platform_budget_day", Long.class)).isZero();
        assertThat(db.queryForObject("select sum(spent_micros) from platform_budget_day", Long.class)).isEqualTo(4000);
        var limiter = app.getBean(LaunchAccessLimiter.class);
        var peer = new LaunchAccessLimiter(app.getBean(StringRedisTemplate.class), app.getEnvironment());
        String lease = limiter.acquire(uid);
        assertThatThrownBy(() -> peer.acquire(uid)).isInstanceOf(LaunchLimitException.class);
        peer.release(uid, "wrong-token");
        assertThatThrownBy(() -> peer.acquire(uid)).isInstanceOf(LaunchLimitException.class);
        limiter.release(uid, lease); peer.release(uid, peer.acquire(uid));
        String rateKey = UUID.randomUUID().toString();
        limiter.hit("verification", rateKey, 2, 60); peer.hit("verification", rateKey, 2, 60);
        assertThatThrownBy(() -> limiter.hit("verification", rateKey, 2, 60)).isInstanceOf(LaunchLimitException.class);
    }

    private static void concurrent(int count, Callable<?> work) throws Exception {
        var pool = Executors.newFixedThreadPool(count);
        var ready = new CountDownLatch(count); var start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < count; i++) futures.add(pool.submit(() -> { ready.countDown(); start.await(); return work.call(); }));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue(); start.countDown();
            for (var future : futures) future.get(60, TimeUnit.SECONDS);
        } finally { pool.shutdownNow(); }
    }
}
