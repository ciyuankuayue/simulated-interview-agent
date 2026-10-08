package com.bupt.mockinterview.rag;

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
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * R2-T0 批 3（D2）非 strict 面：默认（dev/test）上下文下 null 身份向量路径保持现行语义
 * （experiment / dev 启动清理器白名单），{@link RagService} 不抛异常。
 * 与 {@link StrictVectorGuardTest} 成对，锁死 strict 与非 strict 的行为分界（domain.md §7）。
 */
@SpringBootTest
class NullUserVectorPathNonStrictTest {

    private static final int DIM = 1024;

    @Autowired private RagService rag;
    @Autowired private VectorStore store;
    @MockBean private Embedder embedder;

    private String sourceId;

    @BeforeEach
    void clearContext() {
        // 非 strict 上下文 TestTenantContextListener 会填 testuser；本用例验证 null 路径，显式清。
        UserContext.clear();
        TenantContext.clear();
        sourceId = "nonstrict-" + UUID.randomUUID().toString().substring(0, 8);
    }

    @AfterEach
    void cleanup() {
        try {
            UserContext.clear();
            TenantContext.clear();
            // 清掉 null 身份测试块（PUT 空 = 删除该 source 全部块）
            store.beginReplace(null, "note", sourceId);
            store.commitBatch();
        } catch (Exception ignored) {
            // 清理失败不影响断言结论
        }
        TenantContext.clear();
        UserContext.clear();
    }

    @Test
    void nonStrict_nullUser_ingestAndRetrieveRemainAllowed() throws Exception {
        when(embedder.name()).thenReturn("fake:nonstrict");
        when(embedder.embed(anyString())).thenAnswer(inv -> new Embedding(basis(0), 0L, false));

        assertThatCode(() -> rag.ingest("NONSTRICT_MARKER 匿名路径在非 strict 下放行", "note", sourceId))
                .doesNotThrowAnyException();

        assertThatCode(() -> {
            List<KnowledgeChunk.ScoredChunk> hits = rag.retrieve("NONSTRICT_MARKER 查询", 5);
            assertThat(hits).isNotEmpty();
            assertThat(hits.get(0).chunk().userId()).isNull();
            assertThat(hits.get(0).chunk().content()).contains("NONSTRICT_MARKER");
        }).doesNotThrowAnyException();
    }

    private static float[] basis(int axis) {
        float[] v = new float[DIM];
        v[axis] = 1f;
        return v;
    }
}
