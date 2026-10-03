package com.bupt.mockinterview.rag;

import com.bupt.mockinterview.billing.QuotaService;
import com.bupt.mockinterview.domain.User;
import com.bupt.mockinterview.domain.repository.UserRepository;
import com.bupt.mockinterview.security.UserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * R4 批 3：RagService × LuceneVectorStore 用户隔离集成测试（@SpringBootTest，真实装配链路，
 * 测试 profile 用 ByteBuffersDirectory 内存索引；@MockBean Embedder 提供确定性向量）。
 *
 * <p>核心回归：旧 InMemoryVectorStore.search 不带 userId，B 能在向量层看到 A 的块（跨用户泄露）。
 * 这里把 A 的块做成与查询完全同向（余弦≈1，全局 top1），B 自己的块与查询正交——
 * 若隔离失效，B 检索必命中 A；pre-filter 正确时 B 结果里绝不能出现 A 的块。
 */
@SpringBootTest
class RagServiceUserIsolationTest {

    @Autowired
    private RagService rag;
    @Autowired
    private UserRepository userRepo;
    @Autowired
    private QuotaService quotaService;

    @MockBean
    private Embedder embedder;

    private static final int DIM = 1024;

    @AfterEach
    void restoreContext() {
        // 直接调 bean 不走 HTTP，测试基座默认把主线程预置为 testuser；用完清掉避免影响他类。
        UserContext.clear();
    }

    @Test
    void userB_cannotRetrieveUserA_chunks() throws Exception {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        User a = createUserWithQuota("r4a-" + tag);
        User b = createUserWithQuota("r4b-" + tag);

        float[] e0 = basis(0);
        float[] e1 = basis(1);
        when(embedder.name()).thenReturn("fake:isolation");
        when(embedder.embed(anyString())).thenAnswer(inv -> {
            String text = inv.getArgument(0, String.class);
            // B 的资料正交(e1)；A 的资料与查询都同向(e0)
            return new Embedding(text.contains("BBB") ? e1 : e0, 1L, false);
        });

        // A 入库（短文本 → 单 chunk，与查询 e0 同向）
        UserContext.set(a.getId(), a.getUsername());
        rag.ingest("ALIGNED_A 仅 A 可见的项目核心资料，关于向量检索用户隔离的秘密内容。", "note", "901");

        // B 入库（与 e0 正交）
        UserContext.set(b.getId(), b.getUsername());
        rag.ingest("BBB 这是 B 自己的笔记内容，方向与 A 的查询正交。", "note", "902");

        // B 用与 A 完全同向的查询检索：结果里绝不能有 A 的块
        List<KnowledgeChunk.ScoredChunk> bView = rag.retrieve("ALIGNED_A 查询，理论上最该命中 A 的块", 5);
        assertThat(bView).isNotEmpty();
        assertThat(bView).allSatisfy(sc -> {
            assertThat(sc.chunk().userId()).isEqualTo(b.getId());
            assertThat(sc.chunk().content()).doesNotContain("ALIGNED_A");
        });

        // A 自己检索必命中自己的块
        UserContext.set(a.getId(), a.getUsername());
        List<KnowledgeChunk.ScoredChunk> aView = rag.retrieve("ALIGNED_A 查询", 5);
        assertThat(aView).isNotEmpty();
        assertThat(aView.get(0).chunk().userId()).isEqualTo(a.getId());
        assertThat(aView.get(0).chunk().content()).contains("ALIGNED_A");
    }

    private User createUserWithQuota(String username) {
        User user = userRepo.save(new User(username, "x"));
        quotaService.grantIfAbsent(user);
        return user;
    }

    private static float[] basis(int axis) {
        float[] v = new float[DIM];
        v[axis] = 1f;
        return v;
    }
}
