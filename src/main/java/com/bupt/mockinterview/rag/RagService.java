package com.bupt.mockinterview.rag;

import com.bupt.mockinterview.billing.QuotaProperties;
import com.bupt.mockinterview.billing.QuotaService;
import com.bupt.mockinterview.billing.UsageEstimates;
import com.bupt.mockinterview.billing.UsageScene;
import com.bupt.mockinterview.domain.KeyMode;
import com.bupt.mockinterview.security.CurrentUserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * RAG 编排：入库（切块→向量化→存）与检索（查询向量化→top-k 余弦）。
 * Embedder 注入式，便于离线用 {@link FakeEmbedder} 单测、线上换 {@link DashscopeEmbedder}。
 *
 * <p>R2 计量：embedding 恒走平台 key，登录用户入库/检索前预检 token 额度（0 余额直接 402，
 * 不发外部请求），调用成功后按厂商 usage.total_tokens 实扣（漏回 usage 且开启估算时按字符 1:1
 * 兜底）。无用户/离线构造（实验、单测）不计量。
 */
public class RagService {

    private static final Logger log = LoggerFactory.getLogger(RagService.class);

    private final Embedder embedder;
    private final VectorStore store;
    private final int maxChars;
    private final int overlap;
    private final CurrentUserService currentUser;
    private final QuotaService quota;
    private final QuotaProperties quotaProps;

    public RagService(Embedder embedder, VectorStore store) {
        this(embedder, store, 500, 50, null, null, null);
    }

    public RagService(Embedder embedder, VectorStore store, CurrentUserService currentUser) {
        this(embedder, store, 500, 50, currentUser, null, null);
    }

    /** 生产构造：带 token 计量（预检 + 实扣 + 流水）。 */
    public RagService(Embedder embedder, VectorStore store, CurrentUserService currentUser,
                      QuotaService quota, QuotaProperties quotaProps) {
        this(embedder, store, 500, 50, currentUser, quota, quotaProps);
    }

    public RagService(Embedder embedder, VectorStore store, int maxChars, int overlap,
                      CurrentUserService currentUser) {
        this(embedder, store, maxChars, overlap, currentUser, null, null);
    }

    public RagService(Embedder embedder, VectorStore store, int maxChars, int overlap,
                      CurrentUserService currentUser, QuotaService quota, QuotaProperties quotaProps) {
        this.embedder = embedder;
        this.store = store;
        this.maxChars = maxChars;
        this.overlap = overlap;
        this.currentUser = currentUser;
        this.quota = quota;
        this.quotaProps = quotaProps;
    }

    /**
     * 把一份资料（note/resume/project_doc 文本）切块向量化后入库。
     *
     * <p>事务语义（R4）：预检（402 在任何索引动作之前，旧块不动）→ beginReplace 删旧块
     * （pieces 为空也执行 = PUT 清空语义）→ embed+add 循环 → commitBatch；异常 rollbackBatch
     * （Lucene 丢弃本批 delete+add、旧块保持可用；InMemory 立即生效，rollback 为空操作）。
     * 402/409 照常穿透 MaterialController。
     */
    public void ingest(String text, String sourceType, String sourceId) throws Exception {
        List<String> pieces = TextChunker.chunk(text, maxChars, overlap);
        Long userId = billableUserId();
        if (userId != null && !pieces.isEmpty()) {
            // 入库前预检一次：0 余额直接 402，一个 chunk 都不发（旧块完全不动）
            quota.ensureAvailable(userId, KeyMode.PLATFORM);
        }
        store.beginReplace(userId, sourceType, sourceId);
        try {
            for (int i = 0; i < pieces.size(); i++) {
                String piece = pieces.get(i);
                Embedding emb = embedder.embed(piece);
                store.add(new KnowledgeChunk(sourceType + "#" + sourceId + "#" + i, userId,
                        sourceType, sourceId, piece, emb.vector()));
                settle(userId, UsageScene.EMBED_INGEST, piece, emb);
            }
            store.commitBatch();
        } catch (Exception e) {
            store.rollbackBatch();   // Lucene: rollback+重建 writer，旧块保留；InMemory: 空操作
            throw e;                 // 402/409 照常穿透 MaterialController
        }
    }

    /** 给定查询返回 top-k 最相似块（按余弦降序）。 */
    public List<KnowledgeChunk.ScoredChunk> retrieve(String query, int topK) throws Exception {
        Long userId = billableUserId();
        if (userId != null) {
            quota.ensureAvailable(userId, KeyMode.PLATFORM);
        }
        Embedding q = embedder.embed(query);
        settle(userId, UsageScene.EMBED_QUERY, query, q);
        return store.search(userId, q.vector(), topK);
    }

    /** 登录用户且装配了 QuotaService 才计量（离线实验/单测构造返回 null）。 */
    private Long billableUserId() {
        if (currentUser == null || quota == null || quotaProps == null) {
            return null;
        }
        return currentUser.currentUserId();
    }

    /**
     * embedding 实扣：有真实 usage 按真实 token；漏回 usage 且允许估算时按字符 1:1；
     * 其他情况（Fake 向量 / 禁用估算且无 usage）不扣。
     * 扣费独立事务已在 QuotaService 内（REQUIRES_NEW）；记账失败只记 error 不影响入库/检索结果
     *（token 已真实烧给厂商，但绝不能因为流水落库失败把用户的资料上传/面试流程炸掉）。
     */
    private void settle(Long userId, UsageScene scene, String input, Embedding emb) {
        if (userId == null || emb == null) {
            return;
        }
        long tokens = emb.tokens();
        boolean estimated = emb.estimated();
        if (tokens <= 0L) {
            if (!estimated || !quotaProps.isUsageEstimateEnabled()) {
                return;
            }
            tokens = UsageEstimates.estimateChars(input);
        }
        try {
            quota.settlePlatformCall(userId, scene, embedder.name(), 0L, 0L, tokens, estimated);
        } catch (RuntimeException e) {
            log.error("embedding token 结算失败（scene={}, tokens={}），本次未入账：{}",
                    scene, tokens, e.getMessage(), e);
        }
    }
}
