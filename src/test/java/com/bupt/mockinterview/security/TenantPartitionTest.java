package com.bupt.mockinterview.security;

import com.bupt.mockinterview.domain.JdProfile;
import com.bupt.mockinterview.domain.Quota;
import com.bupt.mockinterview.domain.User;
import com.bupt.mockinterview.domain.repository.JdProfileRepository;
import com.bupt.mockinterview.domain.repository.QuotaRepository;
import com.bupt.mockinterview.domain.repository.UserRepository;
import com.bupt.mockinterview.web.SseBroker;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * R2-T0 批 1 spike：Hibernate 6 {@code @TenantId} 分区多租户机制实证（测试先行）。
 * <p>
 * 6 组断言对应 {@code .trae/documents/R2_T0_test_plan.md} §3.2：
 * persist 自动回填 / 跨租户查询过滤 / 三条 Quota 批量 UPDATE 跨租户 affected=0（MED-5）/
 * strict fail-closed 与兜底 0L（L1 部分，窄上下文部分见 {@link TenantStrictContextTest}）/
 * root 哨兵 -1L 边界（HIGH-1）/ SseBroker 纯内存结构（MED-3）。
 * <p>
 * 纪律：用 raw {@link JdbcTemplate} 旁路读列做证伪，不信任 Hibernate 自报；
 * A/B 是本测试独占的新用户 id（=租户 id），其余租户（testuser/兜底 0）的数据不影响断言。
 * <p>
 * <b>本类不加类级 @Transactional</b>：Hibernate 分区模式在 Session/事务开启时只解析一次
 * 租户标识，类事务在 @BeforeEach 之前就以「空上下文→0L」打开，测试体内 set 租户不再生效。
 * 每条仓储调用各自提交（与生产「filter 先 set 上下文、事务后开」同构），用户名带 UUID 防串。
 */
@SpringBootTest
class TenantPartitionTest {

    @Autowired private UserRepository userRepo;
    @Autowired private JdProfileRepository jdRepo;
    @Autowired private QuotaRepository quotaRepo;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private CurrentTenantResolver resolver;
    @Autowired private org.springframework.transaction.PlatformTransactionManager txManager;

    private User a;
    private User b;

    @BeforeEach
    void setUp() {
        TenantContext.clear();
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        a = userRepo.save(new User("tp-a-" + suffix, "hash"));
        b = userRepo.save(new User("tp-b-" + suffix, "hash"));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // ==================== #1 persist 自动回填 tenant_id ====================

    @Test
    void persist_autoFillsTenantId_fromContext() {
        JdProfile jd = new JdProfile();
        jd.setUser(a);
        jd.setRawText("Java 后端岗位 spike A");

        TenantContext.set(a.getId());
        try {
            jdRepo.saveAndFlush(jd);
        } finally {
            TenantContext.clear();
        }

        // 业务代码从未 setTenantId：内存态与落库列都必须被 Hibernate 自动回填为 A
        assertThat(jd.getTenantId()).isEqualTo(a.getId());
        Long rawTenant = jdbc.queryForObject(
                "select tenant_id from jd_profile where id = ?", Long.class, jd.getId());
        assertThat(rawTenant).isEqualTo(a.getId());
    }

    // ==================== #2 跨租户查询自动过滤 ====================

    @Test
    void crossTenant_findAndDerivedQueries_filtered() {
        JdProfile jdA = newJd(a, "JD-A");
        JdProfile jdB = newJd(b, "JD-B");
        saveInTenant(a.getId(), () -> jdRepo.saveAndFlush(jdA));
        saveInTenant(b.getId(), () -> jdRepo.saveAndFlush(jdB));

        // raw 旁路：两行都真实落库，证明后面的不可见是过滤、不是没写进去
        Long rawCount = jdbc.queryForObject(
                "select count(*) from jd_profile where id in (?, ?)", Long.class, jdA.getId(), jdB.getId());
        assertThat(rawCount).isEqualTo(2L);
        assertThat(rawTenant(jdA)).isEqualTo(a.getId());
        assertThat(rawTenant(jdB)).isEqualTo(b.getId());

        TenantContext.set(a.getId());
        try {
            // 【实证缺口·Hibernate 6.5.3】裸 findById 走 em.find 直取（不经 SQM），
            // @TenantId 谓词不生效——跨租户行照样能取出。因此生产代码对分区表禁用裸
            // findById/getById，按 id 取数一律走 findByIdAndUserId（LOW-6 双保险即此语义）。
            // 本断言固化该行为：若 Hibernate 升级后开始过滤，这里变红，提醒我们复查全部 id 通道。
            assertThat(jdRepo.findById(jdB.getId()))
                    .as("em.find 已知缺口：裸 findById 不带租户谓词")
                    .isPresent();
            // 作用域方法走 JPQL/SQM：自动 tenant 谓词 + 显式 user 谓词，双重不命中
            assertThat(jdRepo.findByIdAndUserId(jdB.getId(), a.getId())).isEmpty();
            assertThat(jdRepo.findByIdAndUserId(jdA.getId(), b.getId())).as("显式 user 谓词也不命中").isEmpty();
            assertThat(jdRepo.findByIdAndUserId(jdA.getId(), a.getId())).as("同租户作用域查询命中").isPresent();
            assertThat(jdRepo.findByUser(a)).extracting(JdProfile::getId).containsExactly(jdA.getId());
            // findAll 自动追加 tenant_id=A：租户 id 独占，只有 A 这一条
            assertThat(jdRepo.findAll()).extracting(JdProfile::getId).containsExactly(jdA.getId());
            assertThat(jdRepo.existsByIdAndUserId(jdB.getId(), a.getId())).isFalse();
        } finally {
            TenantContext.clear();
        }

        TenantContext.set(b.getId());
        try {
            assertThat(jdRepo.findByIdAndUserId(jdA.getId(), b.getId())).isEmpty();
            assertThat(jdRepo.findByUser(b)).extracting(JdProfile::getId).containsExactly(jdB.getId());
            assertThat(jdRepo.findAll()).extracting(JdProfile::getId).containsExactly(jdB.getId());
        } finally {
            TenantContext.clear();
        }
    }

    // ==================== #3 Quota 批量 @Query 跨租户 affected=0（MED-5） ====================

    @Test
    void quotaBulkUpdates_crossTenant_affectZero() {
        saveInTenant(a.getId(), () -> quotaRepo.saveAndFlush(new Quota(a, 100)));
        saveInTenant(b.getId(), () -> quotaRepo.saveAndFlush(new Quota(b, 100)));

        // B 租户上下文里对 A 的 userId 执行三条批量 UPDATE：必须全部 affected=0。
        // @Modifying 必须在事务内执行；且租户标识在事务开启时解析，故先 set 上下文再开事务。
        assertThat(bulkInTenant(b.getId(), q -> q.tryChargeTokens(a.getId(), 10))).isZero();
        assertThat(bulkInTenant(b.getId(), q -> q.clampUsedToTotal(a.getId()))).isZero();
        assertThat(bulkInTenant(b.getId(), q -> q.addTotalTokens(a.getId(), 50))).isZero();
        assertThat(rawQuota(a)).as("A 额度必须原样未动").containsExactly(0L, 100L);
        assertThat(rawQuota(b)).as("B 额度不受影响").containsExactly(0L, 100L);

        // A 自己的上下文：三条语句正常生效
        assertThat(bulkInTenant(a.getId(), q -> q.tryChargeTokens(a.getId(), 10))).isEqualTo(1);
        assertThat(bulkInTenant(a.getId(), q -> q.addTotalTokens(a.getId(), 50))).isEqualTo(1);
        // used=10 < total=150：clamp 命中 1 行并归零到 total
        assertThat(bulkInTenant(a.getId(), q -> q.clampUsedToTotal(a.getId()))).isEqualTo(1);
        assertThat(rawQuota(a)).containsExactly(150L, 150L);
        assertThat(rawQuota(b)).as("跨租户串扣检查").containsExactly(0L, 100L);
    }

    // ==================== #4 strict / 兜底（L1；窄上下文见 TenantStrictContextTest） ====================

    @Test
    void resolver_nonStrict_fallbackZeroIsNormalTenant() {
        CurrentTenantResolver nonStrict = new CurrentTenantResolver(new TenantProperties(false));
        TenantContext.clear();
        assertThat(nonStrict.resolveCurrentTenantIdentifier())
                .as("非 strict 缺上下文回退兜底租户 0L").isEqualTo(0L);
        assertThat(nonStrict.isRoot(0L)).as("0L 是受过滤普通租户，绝不是 root").isFalse();
    }

    @Test
    void resolver_strict_missingContext_failsClosed() {
        CurrentTenantResolver strict = new CurrentTenantResolver(new TenantProperties(true));
        strict.markApplicationReady(); // 手工构造的 resolver 无 ready 事件，显式进入就绪后语义
        TenantContext.clear();
        assertThatThrownBy(strict::resolveCurrentTenantIdentifier)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("TENANT_CONTEXT_MISSING");
    }

    // ==================== #5 root 哨兵 -1L 与 runAsRoot 边界（HIGH-1） ====================

    @Test
    void isRoot_onlySentinelMinusOne() {
        assertThat(resolver.isRoot(-1L)).isTrue();
        assertThat(resolver.isRoot(0L)).isFalse();
        assertThat(resolver.isRoot(null)).isFalse();
        assertThat(resolver.isRoot(a.getId())).isFalse();
    }

    @Test
    void runAsRoot_returnsSentinel_andRestoresPreviousContext() {
        TenantContext.set(a.getId());
        assertThat(resolver.resolveCurrentTenantIdentifier()).isEqualTo(a.getId());

        TenantContext.runAsRoot(() -> {
            assertThat(TenantContext.isRoot()).isTrue();
            assertThat(resolver.resolveCurrentTenantIdentifier())
                    .as("root 块内解析为 -1L 哨兵").isEqualTo(-1L);
        });

        assertThat(TenantContext.isRoot()).isFalse();
        assertThat(resolver.resolveCurrentTenantIdentifier()).isEqualTo(a.getId());

        // 块内抛异常也必须恢复
        assertThatThrownBy(() -> TenantContext.runAsRoot(() -> {
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class).hasMessage("boom");
        assertThat(TenantContext.isRoot()).isFalse();
        assertThat(resolver.resolveCurrentTenantIdentifier()).isEqualTo(a.getId());
        TenantContext.clear();

        // 无前置上下文时 root 块结束后恢复为「空」
        TenantContext.runAsRoot(() -> assertThat(resolver.resolveCurrentTenantIdentifier()).isEqualTo(-1L));
        assertThat(TenantContext.getTenantId()).isNull();
        assertThat(TenantContext.isRoot()).isFalse();
    }

    @Test
    void rootSentinel_neverPersisted() {
        saveInTenant(a.getId(), () -> jdRepo.saveAndFlush(newJd(a, "JD-A")));
        saveInTenant(a.getId(), () -> quotaRepo.saveAndFlush(new Quota(a, 10)));
        TenantContext.runAsRoot(() -> {
            // root 块只允许读（本断言刻意不做任何 persist）
            assertThat(TenantContext.isRoot()).isTrue();
        });
        assertThat(jdbc.queryForObject("select count(*) from jd_profile where tenant_id = -1", Long.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from quota where tenant_id = -1", Long.class)).isZero();
    }

    // ==================== #6 SseBroker 纯内存结构（MED-3） ====================

    @Test
    void sseBroker_hasNoPersistenceFields() {
        SseBroker broker = new SseBroker(new ObjectMapper());
        for (Field f : broker.getClass().getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                continue;
            }
            Class<?> t = f.getType();
            assertThat(isPersistenceType(t))
                    .as("SseBroker 字段 %s(%s) 不得是持久化组件——/events 只订阅不查库", f.getName(), t.getName())
                    .isFalse();
        }
    }

    private boolean isPersistenceType(Class<?> t) {
        return org.springframework.data.repository.Repository.class.isAssignableFrom(t)
                || jakarta.persistence.EntityManager.class.isAssignableFrom(t)
                || org.springframework.jdbc.core.JdbcTemplate.class.isAssignableFrom(t)
                || Arrays.stream(t.getInterfaces()).anyMatch(i -> i.getName().endsWith("Repository"))
                || t.getName().endsWith("Repository");
    }

    // ==================== helpers ====================

    private JdProfile newJd(User user, String tag) {
        JdProfile jd = new JdProfile();
        jd.setUser(user);
        jd.setRawText("raw-" + tag);
        return jd;
    }

    private void saveInTenant(Long tenantId, Runnable action) {
        TenantContext.set(tenantId);
        try {
            action.run();
        } finally {
            TenantContext.clear();
        }
    }

    /** 先 set 租户再开事务（Hibernate 在 Session 开启时解析一次租户标识），返回批量语句 affected。 */
    private int bulkInTenant(Long tenantId, java.util.function.ToIntFunction<QuotaRepository> op) {
        TenantContext.set(tenantId);
        try {
            org.springframework.transaction.support.TransactionTemplate tx =
                    new org.springframework.transaction.support.TransactionTemplate(txManager);
            return tx.execute(status -> op.applyAsInt(quotaRepo));
        } finally {
            TenantContext.clear();
        }
    }

    private Long rawTenant(JdProfile jd) {
        return jdbc.queryForObject("select tenant_id from jd_profile where id = ?", Long.class, jd.getId());
    }

    /** 返回 [used_tokens, total_tokens]。 */
    private java.util.List<Long> rawQuota(User user) {
        return jdbc.queryForObject(
                "select used_tokens, total_tokens from quota where user_id = ?",
                (rs, i) -> java.util.List.of(rs.getLong(1), rs.getLong(2)),
                user.getId());
    }
}
