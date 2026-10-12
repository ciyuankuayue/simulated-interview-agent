package com.bupt.mockinterview.it;

import com.bupt.mockinterview.domain.User;
import com.bupt.mockinterview.domain.repository.UserRepository;
import com.bupt.mockinterview.security.CurrentTenantResolver;
import com.bupt.mockinterview.security.JwtService;
import com.bupt.mockinterview.security.TenantContext;
import com.bupt.mockinterview.security.UserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Path;

/**
 * R5-1 集成测试基类：真实 MySQL 8（Docker 3307）+ Redis + 落盘 Lucene。
 * <ul>
 *   <li>{@link #isMysqlReachable()}：MySQL 3307 不可达时全类 skip，不阻断默认 H2 套件。</li>
 *   <li>{@link #indexDir}：每测试类独立 {@link TempDir} 注入 {@code app.rag.index-dir}，
 *       避免多 Spring 上下文争用 FSDirectory 的 {@code write.lock}。</li>
 *   <li>{@link #cleanupAllTables()}：{@code @BeforeEach} 截断全部业务表（先关外键检查），
 *       保证用例间无脏数据（MySQL 持久化，跨测试运行残留）。</li>
 *   <li>strict=true：RagService/Repository 走生产 fail-closed 口径；
 *       直接调 service 的测试必须先 {@link #asUser(User)} 设上下文。</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("mysql-it")
public abstract class MysqlItSupport {

    public static boolean isMysqlReachable() {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("localhost", 3307), 800);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @TempDir
    static Path indexDir;

    @DynamicPropertySource
    static void registerProps(DynamicPropertyRegistry r) {
        r.add("app.rag.index-dir", () -> indexDir.toString());
        // strict=true 要求 APP_DATA_KEY 存在；固定 32 字节 Base64 测试密钥
        r.add("app.security.data-key", () -> "MTIzNDU2Nzg5MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTI=");
    }

    @Autowired protected MockMvc mvc;
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected UserRepository userRepo;
    @Autowired protected JwtService jwtService;

    /** 分区表清单（清表 / 旁路断言用）。顺序无关：先 SET FOREIGN_KEY_CHECKS=0。 */
    private static final String[] TABLES = {
            "interview_turn", "critic_report", "interview_session",
            "weak_point", "llm_usage_log", "knowledge_chunk",
            "project_doc", "real_interview_record", "note",
            "resume_version", "jd_profile", "user_api_key",
            "quota", "app_user"
    };

    @BeforeEach
    void cleanupAllTables() {
        jdbc.execute("SET FOREIGN_KEY_CHECKS=0");
        for (String t : TABLES) {
            jdbc.execute("TRUNCATE TABLE " + t);
        }
        jdbc.execute("SET FOREIGN_KEY_CHECKS=1");
    }

    @AfterEach
    void clearContexts() {
        TenantContext.clear();
        UserContext.clear();
    }

    /**
     * 直接调 service（不走 HTTP）时设当前用户身份 + 租户（MVP 下 tenantId=userId）。
     * strict 下 RagService/Repository 均要求非空身份。
     */
    protected void asUser(User user) {
        UserContext.set(user.getId(), user.getUsername());
        TenantContext.set(user.getId());
    }

    /**
     * 在系统租户（0L）下创建用户：app_user 不分区，但 strict 模式下 Hibernate 开 Session
     * 仍要求租户上下文，故用 0L 兜底。调用方随后可用 {@link #asUser(User)} 切到本人租户。
     */
    protected User createUser(String username) {
        TenantContext.set(CurrentTenantResolver.FALLBACK_TENANT_ID);
        try {
            return userRepo.save(new User(username, "hash"));
        } finally {
            TenantContext.clear();
        }
    }

    /** 生成该用户的 JWT（Bearer token 字符串）。 */
    protected String bearerOf(User user) {
        return "Bearer " + jwtService.generateToken(user.getId(), user.getUsername());
    }

    /** 旁路查 raw tenant_id（绕过 Hibernate @TenantId 过滤，验证分区谓词是否生效）。 */
    protected Long rawTenantIdOf(String table, long id) {
        return jdbc.queryForObject(
                "SELECT tenant_id FROM " + table + " WHERE id = ?", Long.class, id);
    }
}
