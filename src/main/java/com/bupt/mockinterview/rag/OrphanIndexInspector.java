package com.bupt.mockinterview.rag;

import com.bupt.mockinterview.domain.repository.KnowledgeChunkRepository;
import com.bupt.mockinterview.security.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 向量索引孤儿块巡检（R2-T0 批 5，C3）。
 *
 * <p>孤儿 = Lucene 里有向量块、而 {@code knowledge_chunk} 表（及业务源行）已无对应行的来源。
 * userId pre-filter 保证孤儿只可能被<b>本人</b>检索到（不跨租户泄露），但会污染本人召回与计费，
 * 需要给 ADMIN 一个只读巡检 + 人工确认后单点回收的口子（warn-first，绝不自动删）。
 *
 * <p>多租户规约（domain.md §7/§9）：
 * <ul>
 *   <li>枚举走 {@link VectorStore#enumerateSources}（不经 Hibernate）；{@code tenantId=null}
 *       全量枚举是 §9 白名单登记的唯一跨租户枚举，只允许 ADMIN 鉴权后的 controller 调。</li>
 *   <li>存在性校验是分区表查询：逐来源把 {@link TenantContext} 切到该块的 userId 后再调
 *       repository（先 set 后开 Session，每次调用独立事务，本类方法不加 @Transactional），
 *       用完恢复调用前上下文。</li>
 *   <li>回收只走 {@link VectorStore#deleteBySource} 的 AND 语义，且删前再验一次仍是孤儿。</li>
 * </ul>
 */
@Service
public class OrphanIndexInspector {

    private static final Logger log = LoggerFactory.getLogger(OrphanIndexInspector.class);

    /** 向量侧有块、DB 侧无行（userId/sourceId 可正常比对）。 */
    public static final String REASON_VECTOR_WITHOUT_CHUNK = "VECTOR_WITHOUT_CHUNK";
    /** 历史匿名块（userId=null），DB 不可能有对应行。 */
    public static final String REASON_INDEX_ANONYMOUS = "INDEX_ANONYMOUS";
    /** sourceId 非数字，无法映射 knowledge_chunk.sourceId（Long）。 */
    public static final String REASON_SOURCE_ID_NOT_NUMERIC = "SOURCE_ID_NOT_NUMERIC";

    private final VectorStore store;
    private final KnowledgeChunkRepository chunkRepo;

    public OrphanIndexInspector(VectorStore store, KnowledgeChunkRepository chunkRepo) {
        this.store = store;
        this.chunkRepo = chunkRepo;
    }

    /** 巡检报告行。 */
    public record OrphanSource(Long tenantId, String sourceType, String sourceId,
                               int indexedChunks, String reason) {
    }

    /** 删除结果。 */
    public record DeleteResult(int chunksRemoved) {
    }

    /**
     * 列出孤儿来源。
     *
     * @param tenantId 非 null 只巡检该租户；null = 全租户（跨租户白名单，ADMIN only）
     */
    public List<OrphanSource> listOrphans(Long tenantId) {
        List<OrphanSource> orphans = new ArrayList<>();
        for (VectorStore.IndexedSource src : store.enumerateSources(tenantId)) {
            String reason = classify(src);
            if (reason != null) {
                orphans.add(new OrphanSource(src.userId(), src.sourceType(), src.sourceId(),
                        src.chunks(), reason));
            }
        }
        if (!orphans.isEmpty()) {
            log.warn("向量孤儿巡检：tenantId={} 发现 {} 个孤儿来源（仅报告，不自动清理）：{}",
                    tenantId, orphans.size(), orphans);
        }
        return orphans;
    }

    /** 返回孤儿原因；健康来源（向量与行齐备）返回 null。 */
    private String classify(VectorStore.IndexedSource src) {
        if (src.userId() == null) {
            return REASON_INDEX_ANONYMOUS;
        }
        Long sourceIdLong;
        try {
            sourceIdLong = Long.parseLong(src.sourceId());
        } catch (NumberFormatException e) {
            return REASON_SOURCE_ID_NOT_NUMERIC;
        }
        Long previous = TenantContext.getTenantId();
        try {
            TenantContext.set(src.userId());
            boolean exists = chunkRepo.existsByUserIdAndSourceTypeAndSourceId(
                    src.userId(), src.sourceType(), sourceIdLong);
            return exists ? null : REASON_VECTOR_WITHOUT_CHUNK;
        } finally {
            if (previous == null) {
                TenantContext.clear();
            } else {
                TenantContext.set(previous);
            }
        }
    }

    /**
     * 人工确认后回收单个孤儿来源（AND 语义删除）。删前复验：
     * 索引里找不到 → {@link IllegalArgumentException}（404）；
     * 来源已有知识块行（健康，非孤儿）→ {@link IllegalStateException}（409，防误删活资料）。
     *
     * @param tenantId 必须显式指定（禁止匿名/隐式全量删除）
     */
    public DeleteResult deleteOrphan(Long tenantId, String sourceType, String sourceId) {
        if (tenantId == null) {
            throw new IllegalArgumentException("清理孤儿块必须显式指定 tenantId");
        }
        VectorStore.IndexedSource target = store.enumerateSources(tenantId).stream()
                .filter(s -> sourceType.equals(s.sourceType()) && sourceId.equals(s.sourceId()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "向量来源不存在：tenantId=" + tenantId + " " + sourceType + "#" + sourceId));

        Long sourceIdLong;
        try {
            sourceIdLong = Long.parseLong(sourceId);
        } catch (NumberFormatException e) {
            sourceIdLong = null;
        }
        if (sourceIdLong != null) {
            Long previous = TenantContext.getTenantId();
            try {
                TenantContext.set(tenantId);
                if (chunkRepo.existsByUserIdAndSourceTypeAndSourceId(tenantId, sourceType, sourceIdLong)) {
                    throw new IllegalStateException("来源仍有知识块行，拒绝按孤儿清理（请先删业务资料或走正常重建）："
                            + sourceType + "#" + sourceId);
                }
            } finally {
                if (previous == null) {
                    TenantContext.clear();
                } else {
                    TenantContext.set(previous);
                }
            }
        }

        store.deleteBySource(tenantId, sourceType, sourceId);
        log.warn("向量孤儿已按 ADMIN 指令回收：tenantId={} {}#{} 移除块数={}",
                tenantId, sourceType, sourceId, target.chunks());
        return new DeleteResult(target.chunks());
    }
}
