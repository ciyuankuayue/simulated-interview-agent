package com.bupt.mockinterview.rag;

import com.bupt.mockinterview.domain.KnowledgeChunk;
import com.bupt.mockinterview.domain.User;
import com.bupt.mockinterview.domain.repository.KnowledgeChunkRepository;
import com.bupt.mockinterview.domain.repository.UserRepository;
import com.bupt.mockinterview.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * R2-T0 批 5（C3）：{@link OrphanIndexInspector} 三态判定 + 跨租户作用域 + 回收守卫。
 * 默认非 strict 上下文 + 真实 ram LuceneVectorStore；向量直接写 store（不经 embedder），
 * DB 行用实体 repository 造。每方法显式清理向量/行，保持共享上下文干净。
 */
@SpringBootTest
class OrphanIndexInspectorTest {

    private static final int DIM = 1024;

    @Autowired private OrphanIndexInspector inspector;
    @Autowired private VectorStore store;
    @Autowired private UserRepository userRepo;
    @Autowired private KnowledgeChunkRepository chunkRepo;

    private final List<VectorSourceKey> vectorKeys = new ArrayList<>();
    private final List<DbRow> dbRows = new ArrayList<>();
    private User userA;
    private User userB;

    private record VectorSourceKey(Long userId, String sourceType, String sourceId) {}
    private record DbRow(User user, String sourceType, Long sourceId) {}

    @BeforeEach
    void setUp() {
        TenantContext.clear();
        String tag = UUID.randomUUID().toString().substring(0, 8);
        userA = userRepo.save(new User("orphan-a-" + tag, "x"));
        userB = userRepo.save(new User("orphan-b-" + tag, "x"));
    }

    @AfterEach
    void tearDown() {
        for (VectorSourceKey k : vectorKeys) {
            store.deleteBySource(k.userId(), k.sourceType(), k.sourceId());
        }
        for (DbRow r : dbRows) {
            TenantContext.set(r.user().getId());
            chunkRepo.deleteByUserIdAndSourceTypeAndSourceId(r.user().getId(), r.sourceType(), r.sourceId());
        }
        TenantContext.clear();
    }

    private void addVector(Long userId, String sourceType, String sourceId, int chunks) {
        store.beginReplace(userId, sourceType, sourceId);
        for (int i = 0; i < chunks; i++) {
            float[] vec = new float[DIM];
            vec[i % DIM] = 1f;
            store.add(new com.bupt.mockinterview.rag.KnowledgeChunk(
                    sourceType + "#" + sourceId + "#" + i, userId, sourceType, sourceId,
                    "内容 " + sourceType + "#" + sourceId + "#" + i, vec));
        }
        store.commitBatch();
        vectorKeys.add(new VectorSourceKey(userId, sourceType, sourceId));
    }

    private void addDbRow(User user, String sourceType, long sourceId) {
        TenantContext.set(user.getId());
        KnowledgeChunk row = new KnowledgeChunk();
        row.setUser(user);
        row.setSourceType(sourceType);
        row.setSourceId(sourceId);
        row.setChunkText("DB 行 " + sourceType + "#" + sourceId);
        chunkRepo.save(row);
        TenantContext.clear();
        dbRows.add(new DbRow(user, sourceType, sourceId));
    }

    @Test
    void threeStates_healthyOrphanRowOnly_reportsOnlyOrphan_andEnumerationIsReadOnly() {
        addVector(userA.getId(), "note", "1011", 2);
        addDbRow(userA, "note", 1011L);            // 健康：向量 + 行
        addVector(userA.getId(), "note", "1022", 1);  // 孤儿：有向量无行
        addDbRow(userA, "note", 1033L);            // 仅有行：不进报告

        int docsBefore = store.size();
        List<OrphanIndexInspector.OrphanSource> orphans = inspector.listOrphans(userA.getId());
        int docsAfter = store.size();

        assertThat(docsAfter).isEqualTo(docsBefore);   // 枚举只读
        assertThat(orphans).hasSize(1);
        OrphanIndexInspector.OrphanSource o = orphans.get(0);
        assertThat(o.tenantId()).isEqualTo(userA.getId());
        assertThat(o.sourceType()).isEqualTo("note");
        assertThat(o.sourceId()).isEqualTo("1022");
        assertThat(o.indexedChunks()).isEqualTo(1);
        assertThat(o.reason()).isEqualTo(OrphanIndexInspector.REASON_VECTOR_WITHOUT_CHUNK);
    }

    @Test
    void crossTenant_scopedListHidesOthers_allListSeesEverything() {
        addVector(userA.getId(), "note", "2011", 1);   // A 孤儿
        addVector(userB.getId(), "note", "2022", 2);   // B 孤儿

        List<OrphanIndexInspector.OrphanSource> aOnly = inspector.listOrphans(userA.getId());
        List<OrphanIndexInspector.OrphanSource> bOnly = inspector.listOrphans(userB.getId());
        List<OrphanIndexInspector.OrphanSource> all = inspector.listOrphans(null);

        assertThat(aOnly).extracting(OrphanIndexInspector.OrphanSource::sourceId).containsOnly("2011");
        assertThat(bOnly).extracting(OrphanIndexInspector.OrphanSource::sourceId).containsOnly("2022");
        assertThat(all).extracting(OrphanIndexInspector.OrphanSource::sourceId)
                .contains("2011", "2022");
        // 作用域枚举也保证 B 的块不被 A 的枚举看见
        assertThat(store.enumerateSources(userA.getId()))
                .noneMatch(s -> userB.getId().equals(s.userId()));
    }

    @Test
    void anonymousAndNonNumericSources_areReported_andDeletable() {
        addVector(null, "note", "3011", 1);                    // 历史匿名块
        addVector(userA.getId(), "weird", "abc-99", 1);        // sourceId 非数字

        List<OrphanIndexInspector.OrphanSource> all = inspector.listOrphans(null);
        assertThat(all).anyMatch(o -> o.sourceId().equals("3011")
                && o.reason().equals(OrphanIndexInspector.REASON_INDEX_ANONYMOUS)
                && o.tenantId() == null);
        assertThat(all).anyMatch(o -> o.sourceId().equals("abc-99")
                && o.reason().equals(OrphanIndexInspector.REASON_SOURCE_ID_NOT_NUMERIC));

        // 作用域枚举不含匿名块
        assertThat(inspector.listOrphans(userA.getId()))
                .noneMatch(o -> "3011".equals(o.sourceId()));

        // 非数字来源也可按显式租户回收（无法做行存在性校验，但 ADMIN 人审已确认）
        OrphanIndexInspector.DeleteResult r = inspector.deleteOrphan(userA.getId(), "weird", "abc-99");
        assertThat(r.chunksRemoved()).isEqualTo(1);
        assertThat(store.enumerateSources(userA.getId()))
                .noneMatch(s -> "abc-99".equals(s.sourceId()));
    }

    @Test
    void deleteOrphan_removesChunks_andGuardsHealthyAndMissingAndNullTenant() {
        addVector(userA.getId(), "note", "4011", 2);   // 待清理孤儿
        addVector(userA.getId(), "note", "4022", 1);
        addDbRow(userA, "note", 4022L);                // 健康来源

        OrphanIndexInspector.DeleteResult r = inspector.deleteOrphan(userA.getId(), "note", "4011");
        assertThat(r.chunksRemoved()).isEqualTo(2);
        assertThat(store.enumerateSources(userA.getId()))
                .noneMatch(s -> "4011".equals(s.sourceId()));

        // 再删已不存在 → 404
        assertThatThrownBy(() -> inspector.deleteOrphan(userA.getId(), "note", "4011"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("向量来源不存在");

        // 健康来源拒绝删 → 409，向量原样保留
        assertThatThrownBy(() -> inspector.deleteOrphan(userA.getId(), "note", "4022"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("拒绝按孤儿清理");
        assertThat(store.enumerateSources(userA.getId()))
                .anyMatch(s -> "4022".equals(s.sourceId()) && s.chunks() == 1);

        // 禁止隐式全量/匿名删除
        assertThatThrownBy(() -> inspector.deleteOrphan(null, "note", "4011"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("必须显式指定 tenantId");
    }
}
