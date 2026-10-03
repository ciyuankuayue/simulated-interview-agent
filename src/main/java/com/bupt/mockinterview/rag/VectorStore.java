package com.bupt.mockinterview.rag;

import java.util.List;

/**
 * RAG 向量存储抽象（R4 批次 1）。
 *
 * <p>两个实现：
 * <ul>
 *   <li>{@link InMemoryVectorStore}：MVP 内存版，离线单测/实验用</li>
 *   <li>{@code LuceneVectorStore}（批次 2）：生产持久化，FSDirectory + HNSW + 用户隔离</li>
 * </ul>
 *
 * <p>userId 过滤语义：null = 离线/实验路径，不加用户条件；非 null = 登录用户，
 * 检索与删除都按 userId 隔离。
 *
 * <p>批次事务：一次 ingest = beginReplace（删旧）→ 多次 add → commitBatch（提交）；
 * 异常走 rollbackBatch（丢弃本批未提交的 delete+add，旧块保持可用）。
 * InMemory 实现立即生效，commit/rollback 为空操作。
 */
public interface VectorStore {

    /** 追加一个 chunk 到当前批次。 */
    void add(KnowledgeChunk chunk);

    /** 检索 topK 最相似块。userId=null 不过滤（离线/实验）。 */
    List<KnowledgeChunk.ScoredChunk> search(Long userId, float[] queryVec, int topK);

    /**
     * 按 (userId, sourceType, sourceId) 删除，AND 语义（三者全匹配才删）。
     * userId=null 时省略 userId 条件。
     */
    void deleteBySource(Long userId, String sourceType, String sourceId);

    /** 清空整个索引（dev 启动清孤儿块 / 测试用）。 */
    void deleteAll();

    /** 全局 doc 数（跨用户，含逻辑删除待合并）；仅粗监控。 */
    int size();

    /** 关闭底层资源（Lucene writer/directory；InMemory 空实现）。 */
    void close();

    /**
     * 开始一个 replace 批次：删除同 (userId, sourceType, sourceId) 的旧块（PUT 覆盖语义）。
     * pieces 为空也执行 = PUT 清空语义。默认立即删（InMemory）；Lucene 在 commitBatch 后才可见。
     */
    default void beginReplace(Long userId, String sourceType, String sourceId) {
        deleteBySource(userId, sourceType, sourceId);
    }

    /** 提交当前批次（Lucene commit + 刷新 NRT reader）；默认空实现。 */
    default void commitBatch() { /* InMemory 立即生效，无需提交 */ }

    /** 回滚当前批次（Lucene rollback + 重建 writer，旧块保留）；默认空实现。 */
    default void rollbackBatch() { /* InMemory 无事务可回滚 */ }
}
