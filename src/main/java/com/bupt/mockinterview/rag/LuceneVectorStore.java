package com.bupt.mockinterview.rag;

import jakarta.annotation.PreDestroy;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.cjk.CJKAnalyzer;
import org.apache.lucene.analysis.miscellaneous.PerFieldAnalyzerWrapper;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.IntPoint;
import org.apache.lucene.document.KnnVectorField;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.KnnVectorQuery;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.store.LockObtainFailedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Lucene FSDirectory 持久化向量存储（R4 生产实现）。
 *
 * <p>核心设计（详见 {@code .trae/documents/R4_lucene_vector_persistence_plan.md} v2）：
 * <ul>
 *   <li><b>持久化</b>：FSDirectory + CREATE_OR_APPEND（显式指定，绝不依赖默认值误清空）；
 *       进程重启后索引不丢。</li>
 *   <li><b>向量口径</b>：写入/查询统一 pad-0/truncate 到配置维度后 L2 归一化，Lucene 默认
 *       EUCLIDEAN 在归一化向量上排序等价余弦。</li>
 *   <li><b>用户隔离（pre-filter）</b>：登录用户用 4 参
 *       {@link KnnVectorQuery#KnnVectorQuery(String, float[], int, org.apache.lucene.search.Query)}
 *       把 userId TermQuery 作为 filter 下推到 HNSW 图遍历期，别人的块不占召回名额；
 *       离线（userId=null）用 3 参无 filter。</li>
 *   <li><b>删除 AND 语义</b>：deleteBySource 用 BooleanQuery MUST×3（userId+sourceType+sourceId），
 *       严禁 deleteDocuments(Term...)（Term 变长参是 OR 语义，会删光该用户全部资料）。</li>
 *   <li><b>批次事务</b>：beginReplace（缓冲删旧）→ add 循环 → commitBatch（commit + 切 NRT reader）；
 *       异常 rollbackBatch（writer.rollback 丢弃本批 + 锁内重建 writer，旧已提交块保持可用）。</li>
 *   <li><b>读一致性</b>：NRT reader 只在构造后、commitBatch 后、rollback/deleteAll 后刷新；
 *       search 不刷新——上传进行中的检索命中旧快照，提交后才看到新块。</li>
 *   <li><b>串行写</b>：一把 {@link ReentrantLock} 串行化整个批次区间；search 走独立监视器，
 *       不被写锁阻塞。</li>
 * </ul>
 */
public class LuceneVectorStore implements VectorStore, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(LuceneVectorStore.class);

    static final String FIELD_ID = "id";
    static final String FIELD_USER_ID = "userId";
    static final String FIELD_SOURCE_TYPE = "sourceType";
    static final String FIELD_SOURCE_ID = "sourceId";
    static final String FIELD_CHUNK_IDX = "chunkIdx";
    static final String FIELD_CONTENT = "content";
    static final String FIELD_CONTENT_TEXT = "contentText";
    static final String FIELD_VEC = "vec";

    /** 中文分词器（bigram），用于 contentText 字段的 BM25 索引与查询分词；StandardAnalyzer 对中文是单字切分。 */
    private static final Analyzer CJK = new CJKAnalyzer();

    private final int dim;
    private final Path dir;

    private final Directory directory;
    private IndexWriter writer;
    /** 当前检索快照；只在构造后 / commitBatch / rollback / deleteAll 后切换。volatile 保证可见性。 */
    private volatile DirectoryReader reader;
    private volatile IndexSearcher searcher;

    /** 串行化 beginReplace→commit/rollback 整个批次区间。 */
    private final ReentrantLock writeLock = new ReentrantLock();
    /** reader 切换与 search 互斥，避免 close 旧 reader 时有查询在用（与写锁分离，search 不阻塞写）。 */
    private final Object readMonitor = new Object();

    public LuceneVectorStore(Path indexDir, int dim) throws IOException {
        this(openFsDirectory(indexDir), dim, indexDir);
    }

    /**
     * 指定底层 {@link Directory} 的构造器（测试用 ByteBuffersDirectory 内存目录，
     * 走与生产完全相同的 HNSW/pre-filter/事务逻辑，仅不落盘）。
     */
    public LuceneVectorStore(Directory directory, int dim) throws IOException {
        this(directory, dim, null);
    }

    private LuceneVectorStore(Directory directory, int dim, Path indexDir) throws IOException {
        if (dim <= 0) {
            throw new IllegalArgumentException("向量维度必须为正数：" + dim);
        }
        this.dim = dim;
        this.dir = indexDir;
        this.directory = directory;
        try {
            this.writer = new IndexWriter(directory, newWriterConfig());
            this.reader = DirectoryReader.open(writer);
            this.searcher = new IndexSearcher(reader);
        } catch (IOException e) {
            throw new IllegalStateException("初始化向量索引失败（索引可能已损坏）："
                    + (indexDir != null ? indexDir.toAbsolutePath() : directory)
                    + "。请备份后删除或更换 app.rag.index-dir 目录再重启（不会自动静默重建，以免丢数据）。", e);
        }
        checkVectorDimension();
        if (indexDir != null) {
            log.info("向量索引目录：{}（dim={}，全局存活 doc={}）", indexDir.toAbsolutePath(), dim, reader.numDocs());
        } else {
            log.info("向量索引使用内存目录 {}（dim={}，存活 doc={}）", directory, dim, reader.numDocs());
        }
    }

    /** 索引位置描述（落盘给绝对路径，内存目录给 directory 名）。 */
    private String location() {
        return dir != null ? "目录 " + dir.toAbsolutePath() : String.valueOf(directory);
    }

    /** 打开 FSDirectory，单独捕获写锁占用 / 损坏，给中文指引；不静默重建。 */
    private static Directory openFsDirectory(Path indexDir) throws IOException {
        try {
            Files.createDirectories(indexDir);
            return FSDirectory.open(indexDir);
        } catch (LockObtainFailedException e) {
            throw new IllegalStateException("向量索引目录被其他进程占用（write.lock 未释放）：" + indexDir.toAbsolutePath()
                    + "。请先检查是否有残留的应用进程占用该目录，删除 write.lock 前务必确认无进程在写。", e);
        } catch (IOException e) {
            throw new IllegalStateException("打开向量索引失败（索引可能已损坏）：" + indexDir.toAbsolutePath()
                    + "。请备份后删除或更换 app.rag.index-dir 目录再重启（不会自动静默重建，以免丢数据）。", e);
        }
    }

    private IndexWriterConfig newWriterConfig() {
        // contentText 走 CJK 分词做 BM25；其余字段（userId/source 等均为 StringField，不参与分词）用 StandardAnalyzer。
        Map<String, Analyzer> fieldAnalyzers = new HashMap<>();
        fieldAnalyzers.put(FIELD_CONTENT_TEXT, CJK);
        Analyzer analyzer = new PerFieldAnalyzerWrapper(new StandardAnalyzer(), fieldAnalyzers);
        IndexWriterConfig cfg = new IndexWriterConfig(analyzer);
        // 显式 CREATE_OR_APPEND：目录有旧索引就追加，没有才新建，绝不 CREATE（误清空）。
        cfg.setOpenMode(IndexWriterConfig.OpenMode.CREATE_OR_APPEND);
        return cfg;
    }

    /**
     * 已存在索引的向量维度必须与当前配置一致（换 embedding 模型维度不同时禁止混用）。
     * 同名字段的向量维度由 Lucene 保证跨 segment 一致，取首个含该字段的 leaf 即可。
     */
    private void checkVectorDimension() throws IOException {
        for (LeafReaderContext ctx : reader.leaves()) {
            FieldInfo fi = ctx.reader().getFieldInfos().fieldInfo(FIELD_VEC);
            if (fi == null) {
                continue;
            }
            int existing = fi.getVectorDimension();
            if (existing > 0 && existing != dim) {
                throw new IllegalStateException("向量维度不一致：索引 " + location()
                        + " 已有的 vec 维度为 " + existing + "，当前配置 app.rag.dim=" + dim
                        + "。若更换了 embedding 模型，请删除或更换 app.rag.index-dir 后重启（不同维度的向量不可混用）。");
            }
            return;
        }
    }

    // —— 批次事务边界 ——

    /**
     * 开始 replace 批次：缓冲删除同 (userId, sourceType, sourceId) 的旧块。
     * <b>删除在 commitBatch 前不可见</b>（并发检索仍命中旧快照）。持有写锁直到 commit/rollback。
     */
    @Override
    public void beginReplace(Long userId, String sourceType, String sourceId) {
        writeLock.lock();
        try {
            writer.deleteDocuments(buildSourceQuery(userId, sourceType, sourceId));
        } catch (IOException e) {
            writeLock.unlock();
            throw new IllegalStateException("beginReplace 删除旧块失败：" + sourceType + "#" + sourceId, e);
        }
    }

    /** 独立删除调用（不经 beginReplace 时立即提交生效）。AND 语义。 */
    @Override
    public void deleteBySource(Long userId, String sourceType, String sourceId) {
        writeLock.lock();
        try {
            writer.deleteDocuments(buildSourceQuery(userId, sourceType, sourceId));
            writer.commit();
            refreshReader();
        } catch (IOException e) {
            throw new IllegalStateException("deleteBySource 失败：" + sourceType + "#" + sourceId, e);
        } finally {
            if (writeLock.isHeldByCurrentThread()) {
                writeLock.unlock();
            }
        }
    }

    /** userId（非 null）+ sourceType + sourceId 三者 MUST 的 AND 查询；userId=null 省略该子句。 */
    private BooleanQuery buildSourceQuery(Long userId, String sourceType, String sourceId) {
        BooleanQuery.Builder b = new BooleanQuery.Builder();
        if (userId != null) {
            b.add(new TermQuery(new Term(FIELD_USER_ID, String.valueOf(userId))), BooleanClause.Occur.MUST);
        }
        b.add(new TermQuery(new Term(FIELD_SOURCE_TYPE, sourceType)), BooleanClause.Occur.MUST);
        b.add(new TermQuery(new Term(FIELD_SOURCE_ID, sourceId)), BooleanClause.Occur.MUST);
        return b.build();
    }

    @Override
    public void add(KnowledgeChunk chunk) {
        try {
            writer.addDocument(toDocument(chunk));
        } catch (IOException e) {
            throw new IllegalStateException("写入向量块失败：" + chunk.id(), e);
        }
    }

    @Override
    public void commitBatch() {
        try {
            writer.commit();
            refreshReader();
        } catch (IOException e) {
            throw new IllegalStateException("提交向量批次失败", e);
        } finally {
            if (writeLock.isHeldByCurrentThread()) {
                writeLock.unlock();
            }
        }
    }

    /**
     * 回滚：{@link IndexWriter#rollback()} 丢弃自上次 commit 以来全部缓冲 delete+add 并关闭 writer；
     * 锁内重建 writer + reader，旧已提交块保持可用。
     */
    @Override
    public void rollbackBatch() {
        boolean locked = writeLock.isHeldByCurrentThread();
        try {
            writer.rollback(); // 关闭 writer；本批未提交内容全部丢弃
            rebuildWriterAndReader();
        } catch (IOException e) {
            throw new IllegalStateException("回滚向量批次后重建 writer 失败，索引可能需人工检查：" + dir, e);
        } finally {
            if (locked) {
                writeLock.unlock();
            }
        }
    }

    /** rollback 关闭 writer 后，在写锁内重建 writer 与 NRT reader（状态回到上次 commit）。 */
    private void rebuildWriterAndReader() throws IOException {
        this.writer = new IndexWriter(directory, newWriterConfig());
        DirectoryReader nr = DirectoryReader.open(writer);
        synchronized (readMonitor) {
            DirectoryReader old = this.reader;
            this.reader = nr;
            this.searcher = new IndexSearcher(nr);
            closeQuietly(old);
        }
    }

    @Override
    public void deleteAll() {
        writeLock.lock();
        try {
            writer.deleteAll();
            writer.commit(); // 立即持久化清空（StartupIndexCleaner 启动期调用）
            refreshReader();
        } catch (IOException e) {
            throw new IllegalStateException("清空向量索引失败：" + dir, e);
        } finally {
            if (writeLock.isHeldByCurrentThread()) {
                writeLock.unlock();
            }
        }
    }

    /** commit / deleteAll 后切换 NRT reader 到最新已提交快照。 */
    private void refreshReader() throws IOException {
        synchronized (readMonitor) {
            DirectoryReader nr = DirectoryReader.openIfChanged(reader, writer);
            if (nr != null) {
                DirectoryReader old = this.reader;
                this.reader = nr;
                this.searcher = new IndexSearcher(nr);
                closeQuietly(old);
            }
        }
    }

    // —— 检索 ——

    @Override
    public List<KnowledgeChunk.ScoredChunk> search(Long userId, float[] queryVec, int topK) {
        if (topK <= 0) {
            return List.of();
        }
        float[] q = fitAndNormalize(queryVec);
        IndexSearcher s;
        int total;
        synchronized (readMonitor) {
            s = this.searcher;
            total = reader.numDocs();
        }
        if (total == 0) {
            return List.of();
        }

        Query query;
        int fetch;
        if (userId != null) {
            // pre-filter：filter 在 HNSW 遍历期生效，候选放大保证资料极少的用户也能召回自己。
            int candidateK = Math.max(topK * 3, 30);
            fetch = candidateK;
            query = new KnnVectorQuery(FIELD_VEC, q, candidateK,
                    new TermQuery(new Term(FIELD_USER_ID, String.valueOf(userId))));
        } else {
            fetch = topK;
            query = new KnnVectorQuery(FIELD_VEC, q, topK);
        }
        int n = Math.max(1, Math.min(fetch, total));
        try {
            TopDocs top = s.search(query, n);
            List<KnowledgeChunk.ScoredChunk> out = new ArrayList<>(Math.min(topK, top.scoreDocs.length));
            for (ScoreDoc sd : top.scoreDocs) {
                if (out.size() >= topK) {
                    break;
                }
                Document d = s.doc(sd.doc);
                out.add(new KnowledgeChunk.ScoredChunk(toChunk(d), sd.score));
            }
            return out;
        } catch (IOException e) {
            throw new IllegalStateException("向量检索失败", e);
        }
    }

    /**
     * 只读枚举（R2-T0 批 5 C3）：扫描已提交快照，按 (userId, sourceType, sourceId) 聚合块数。
     * userId 非 null 用 TermQuery 下推；null = MatchAll 全量（跨租户白名单，仅 ADMIN 巡检）。
     */
    @Override
    public List<IndexedSource> enumerateSources(Long userId) {
        IndexSearcher s;
        int total;
        synchronized (readMonitor) {
            s = this.searcher;
            total = reader.numDocs();
        }
        if (total == 0) {
            return List.of();
        }
        Query query = userId != null
                ? new TermQuery(new Term(FIELD_USER_ID, String.valueOf(userId)))
                : new MatchAllDocsQuery();
        try {
            TopDocs top = s.search(query, total);
            // key = userId(空串表匿名) + \u0000 + sourceType + \u0000 + sourceId
            Map<String, long[]> counts = new LinkedHashMap<>();
            for (ScoreDoc sd : top.scoreDocs) {
                Document d = s.doc(sd.doc);
                String uid = d.get(FIELD_USER_ID);
                String key = (uid == null ? "" : uid) + '\u0000'
                        + d.get(FIELD_SOURCE_TYPE) + '\u0000' + d.get(FIELD_SOURCE_ID);
                counts.computeIfAbsent(key, k -> new long[1])[0]++;
            }
            List<IndexedSource> out = new ArrayList<>(counts.size());
            for (Map.Entry<String, long[]> e : counts.entrySet()) {
                String[] parts = e.getKey().split("\u0000", 3);
                Long uid = parts[0].isEmpty() ? null : Long.valueOf(parts[0]);
                out.add(new IndexedSource(uid, parts[1], parts[2], (int) e.getValue()[0]));
            }
            out.sort(Comparator
                    .comparing(IndexedSource::userId, Comparator.nullsFirst(Comparator.naturalOrder()))
                    .thenComparing(IndexedSource::sourceType)
                    .thenComparing(IndexedSource::sourceId));
            return out;
        } catch (IOException e) {
            throw new IllegalStateException("枚举向量来源失败", e);
        }
    }

    // —— 检索扩展：BM25 全文检索 + 混合 RRF 融合（RAG 评测 / 召回优化用，不破坏现有 search） ——

    /**
     * BM25 全文检索：查询经 CJK 分词后布尔 OR，userId 作为 {@code FILTER} 下推（不参与打分，仅过滤）。
     * 与 {@link #search} 互补——向量擅长语义近义，BM25 擅长中文技术专有名词精确命中。
     */
    public List<KnowledgeChunk.ScoredChunk> searchKeyword(Long userId, String query, int topK) {
        if (topK <= 0 || query == null || query.isBlank()) {
            return List.of();
        }
        IndexSearcher s;
        int total;
        synchronized (readMonitor) {
            s = this.searcher;
            total = reader.numDocs();
        }
        if (total == 0) {
            return List.of();
        }
        List<String> tokens = analyze(query);
        if (tokens.isEmpty()) {
            return List.of();
        }
        BooleanQuery.Builder kw = new BooleanQuery.Builder();
        for (String t : tokens) {
            kw.add(new TermQuery(new Term(FIELD_CONTENT_TEXT, t)), BooleanClause.Occur.SHOULD);
        }
        BooleanQuery.Builder b = new BooleanQuery.Builder();
        b.add(kw.build(), BooleanClause.Occur.MUST);
        if (userId != null) {
            b.add(new TermQuery(new Term(FIELD_USER_ID, String.valueOf(userId))), BooleanClause.Occur.FILTER);
        }
        int n = Math.max(1, Math.min(topK, total));
        try {
            TopDocs top = s.search(b.build(), n);
            List<KnowledgeChunk.ScoredChunk> out = new ArrayList<>(top.scoreDocs.length);
            for (ScoreDoc sd : top.scoreDocs) {
                Document d = s.doc(sd.doc);
                out.add(new KnowledgeChunk.ScoredChunk(toChunk(d), sd.score));
            }
            return out;
        } catch (IOException e) {
            throw new IllegalStateException("BM25 检索失败", e);
        }
    }

    /** 混合检索：向量 topN + BM25 topN 经 RRF 融合（k 默认 60）。返回融合后 topK。 */
    public List<KnowledgeChunk.ScoredChunk> searchHybrid(Long userId, float[] queryVec, String query, int topK) {
        return searchHybrid(userId, queryVec, query, topK, 60);
    }

    public List<KnowledgeChunk.ScoredChunk> searchHybrid(Long userId, float[] queryVec, String query, int topK, int k) {
        if (topK <= 0) {
            return List.of();
        }
        int n = Math.max(topK * 3, 30);
        Map<String, Double> fused = new LinkedHashMap<>();
        Map<String, KnowledgeChunk> byId = new HashMap<>();
        List<KnowledgeChunk.ScoredChunk> v = search(userId, queryVec, n);
        for (int i = 0; i < v.size(); i++) {
            String id = v.get(i).chunk().id();
            fused.merge(id, 1.0 / (k + i + 1), Double::sum);
            byId.putIfAbsent(id, v.get(i).chunk());
        }
        List<KnowledgeChunk.ScoredChunk> bm = searchKeyword(userId, query, n);
        for (int i = 0; i < bm.size(); i++) {
            String id = bm.get(i).chunk().id();
            fused.merge(id, 1.0 / (k + i + 1), Double::sum);
            byId.putIfAbsent(id, bm.get(i).chunk());
        }
        return fused.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(topK)
                .map(e -> new KnowledgeChunk.ScoredChunk(byId.get(e.getKey()), e.getValue()))
                .toList();
    }

    /** 用 CJKAnalyzer 把查询文本切成词项（与索引端同分析器，保证 BM25 命中）。 */
    private List<String> analyze(String text) {
        List<String> out = new ArrayList<>();
        try (TokenStream ts = CJK.tokenStream(FIELD_CONTENT_TEXT, text)) {
            CharTermAttribute cat = ts.addAttribute(CharTermAttribute.class);
            ts.reset();
            while (ts.incrementToken()) {
                out.add(cat.toString());
            }
            ts.end();
        } catch (IOException e) {
            throw new IllegalStateException("查询分词失败", e);
        }
        return out;
    }

    private Document toDocument(KnowledgeChunk c) {
        Document d = new Document();
        d.add(new StringField(FIELD_ID, c.id(), StringField.Store.YES));
        if (c.userId() != null) {
            d.add(new StringField(FIELD_USER_ID, String.valueOf(c.userId()), StringField.Store.YES));
        }
        d.add(new StringField(FIELD_SOURCE_TYPE, c.sourceType(), StringField.Store.YES));
        d.add(new StringField(FIELD_SOURCE_ID, c.sourceId(), StringField.Store.YES));
        d.add(new IntPoint(FIELD_CHUNK_IDX, parseChunkIdx(c.id())));
        // contentText：BM25 全文检索用，走 CJK 分词（不存原文，省空间）；content 仍作 StoredField 供结果回放。
        d.add(new TextField(FIELD_CONTENT_TEXT, c.content(), Field.Store.NO));
        d.add(new StoredField(FIELD_CONTENT, c.content()));
        // 向量不做 StoredField（检索结果不需要回放向量，省空间）；读取侧 embedding=null。
        d.add(new KnnVectorField(FIELD_VEC, fitAndNormalize(c.embedding())));
        return d;
    }

    private KnowledgeChunk toChunk(Document d) {
        String uid = d.get(FIELD_USER_ID);
        Long userId = uid == null ? null : Long.valueOf(uid);
        return new KnowledgeChunk(
                d.get(FIELD_ID),
                userId,
                d.get(FIELD_SOURCE_TYPE),
                d.get(FIELD_SOURCE_ID),
                d.get(FIELD_CONTENT),
                null
        );
    }

    /** id 形如 sourceType#sourceId#idx，取最后一段作为 chunkIdx；解析失败给 -1。 */
    private static int parseChunkIdx(String id) {
        try {
            int p = id.lastIndexOf('#');
            return p < 0 ? -1 : Integer.parseInt(id.substring(p + 1));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** pad-0/truncate 到配置维度，再 L2 归一化（归一化后欧氏排序等价余弦）。零向量原样返回。 */
    private float[] fitAndNormalize(float[] raw) {
        float[] v = new float[dim];
        System.arraycopy(raw, 0, v, 0, Math.min(raw.length, dim));
        double sumSq = 0;
        for (float x : v) {
            sumSq += (double) x * x;
        }
        double n = Math.sqrt(sumSq);
        if (n == 0) {
            return v;
        }
        for (int i = 0; i < dim; i++) {
            v[i] = (float) (v[i] / n);
        }
        return v;
    }

    /**
     * 全局<b>存活</b> Lucene doc 总数（跨用户；不含已逻辑删除待合并的旧 replace 版本），仅作粗监控；
     * 取已提交快照的 numDocs，不随进行中的未提交批次跳动。
     */
    @Override
    public int size() {
        synchronized (readMonitor) {
            return reader.numDocs();
        }
    }

    @PreDestroy
    @Override
    public void close() {
        writeLock.lock();
        try {
            synchronized (readMonitor) {
                closeQuietly(reader);
                closeWriter();
                closeQuietly(directory);
            }
        } finally {
            if (writeLock.isHeldByCurrentThread()) {
                writeLock.unlock();
            }
        }
    }

    private void closeWriter() {
        try {
            if (writer != null) {
                writer.close(); // close 前会 flush 未提交内容；正常流程批次均已 commit
            }
        } catch (Exception e) {
            log.warn("关闭 IndexWriter 失败（忽略，继续关闭目录）：{}", e.getMessage());
        } finally {
            writer = null;
        }
    }

    private static void closeQuietly(AutoCloseable c) {
        if (c == null) {
            return;
        }
        try {
            c.close();
        } catch (Exception ignore) {
            // 关闭失败不影响主流程
        }
    }
}
