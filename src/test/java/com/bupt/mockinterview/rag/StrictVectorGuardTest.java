package com.bupt.mockinterview.rag;

import com.bupt.mockinterview.billing.QuotaService;
import com.bupt.mockinterview.domain.User;
import com.bupt.mockinterview.domain.repository.UserRepository;
import com.bupt.mockinterview.security.CurrentTenantResolver;
import com.bupt.mockinterview.security.TenantContext;
import com.bupt.mockinterview.security.UserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R2-T0 批 3（D2）：strict（prod 口径）下 {@link RagService} 的 null 身份向量路径
 * 必须 fail-closed（domain.md §7）。独立 strict 上下文（与 TenantStrictContextTest 同配置，
 * 共享同一 ram 目录上下文）；@MockBean Embedder 保证零外部调用、向量确定性。
 */
@SpringBootTest(properties = {
        "app.tenant.strict=true",
        "jwt.secret=01234567890123456789012345678901",
        "app.security.data-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
})
class StrictVectorGuardTest {

    private static final int DIM = 1024;

    @Autowired private RagService rag;
    @Autowired private VectorStore store;
    @Autowired private UserRepository userRepo;
    @Autowired private QuotaService quotaService;

    @MockBean private Embedder embedder;

    @BeforeEach
    void clearContext() {
        // strict 上下文 TestTenantContextListener 不填充；显式清，防启动期 testUserSetup 残留。
        UserContext.clear();
        TenantContext.clear();
    }

    @AfterEach
    void restoreContext() {
        TenantContext.clear();
        UserContext.clear();
    }

    @Test
    void strict_ingestNullUser_throwsBeforeAnyWrite() throws Exception {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        assertThatThrownBy(() -> rag.ingest("一段本该被拒绝入库的资料内容", "note", "guard-" + tag))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(RagService.VECTOR_USER_MISSING);

        // 一个 embedding 请求都不许发出
        verify(embedder, never()).embed(anyString());

        // 索引里不许出现 null 身份块（3 参无 filter 直读证伪）
        List<KnowledgeChunk.ScoredChunk> nullView =
                store.search(null, basis(0), 5);
        assertThat(nullView).isEmpty();
    }

    @Test
    void strict_retrieveNullUser_throwsBeforeEmbedding() throws Exception {
        assertThatThrownBy(() -> rag.retrieve("任意查询", 5))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(RagService.VECTOR_USER_MISSING);
        verify(embedder, never()).embed(anyString());
    }

    @Test
    void strict_explicitUser_ingestRetrieveRoundTripWorks() throws Exception {
        // 显式身份的登录路径不能被守卫误伤
        String tag = UUID.randomUUID().toString().substring(0, 8);
        // strict 下建 Session 即要上下文（app_user 虽不分区）：先 0L 壳建 user（domain.md §9）
        TenantContext.set(CurrentTenantResolver.FALLBACK_TENANT_ID);
        User user = userRepo.save(new User("vecguard-" + tag, "x"));
        TenantContext.set(user.getId());
        try {
            quotaService.grantIfAbsent(user);
        } finally {
            TenantContext.clear();
        }

        when(embedder.name()).thenReturn("fake:guard");
        when(embedder.embed(anyString())).thenAnswer(inv -> new Embedding(basis(0), 1L, false));

        String sourceId = "ok-" + tag;
        UserContext.set(user.getId(), user.getUsername());
        TenantContext.set(user.getId());
        try {
            rag.ingest("GUARD_MARKER 登录用户的入库与检索必须正常工作", "note", sourceId);

            List<KnowledgeChunk.ScoredChunk> hits = rag.retrieve("GUARD_MARKER 查询", 5);
            assertThat(hits).isNotEmpty();
            assertThat(hits.get(0).chunk().userId()).isEqualTo(user.getId());
            assertThat(hits.get(0).chunk().content()).contains("GUARD_MARKER");
        } finally {
            // 清掉本方法写入的向量，保持共享 strict 上下文干净
            store.beginReplace(user.getId(), "note", sourceId);
            store.commitBatch();
            UserContext.clear();
            TenantContext.clear();
        }
    }

    private static float[] basis(int axis) {
        float[] v = new float[DIM];
        v[axis] = 1f;
        return v;
    }
}
