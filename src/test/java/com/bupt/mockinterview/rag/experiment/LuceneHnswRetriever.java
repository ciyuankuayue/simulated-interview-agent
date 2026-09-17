package com.bupt.mockinterview.rag.experiment;

import com.bupt.mockinterview.rag.Embedder;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.KnnVectorField;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.KnnVectorQuery;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.apache.lucene.store.Directory;

import java.util.ArrayList;
import java.util.List;

/**
 * L3 向量库侧：Apache Lucene 的 HNSW 向量索引（lucene-core，Maven Central，纯 Java、零原生库）。
 *
 * 与 FAISS 同属 ANN 算法族（HNSW vs FAISS 的 IVF/PQ），是 OpenSearch / Elasticsearch
 * 使用的同一套向量检索实现，也是 Java 生态里最地道的「真向量库」。
 *
 * 选型说明（2026-09-18）：FAISS 的 Java 绑定在 Maven Central 上不存在
 * （bytedeco 无 faiss 预设；karellen/faiss-java、jelmerk/faiss4j 均 404），
 * 只能走 JitPack 且实测 401，故改用 Lucene HNSW 承担同一实验角色。
 *
 * 相似度口径：入库前对向量做 L2 归一化，Lucene 默认 EUCLIDEAN 距离在归一化向量上的
 * 排序与余弦完全等价（||a-b||^2 = 2 - 2cos），故命中结果与手写余弦一致；
 * 但 Lucene 返回的 score 是其内部打分（约 1/(1+dist)），与手写侧的余弦值不可直接比较，
 * 对比只取排序与 recall@k。
 */
public class LuceneHnswRetriever implements Retriever {

    private static final String FIELD_VEC = "vec";
    private static final String FIELD_ID = "id";

    private final Embedder embedder;
    private final List<String> ids = new ArrayList<>();
    private final List<float[]> vecs = new ArrayList<>();

    private Directory directory;
    private IndexWriter writer;
    private DirectoryReader reader;
    private IndexSearcher searcher;
    private boolean built = false;

    public LuceneHnswRetriever(Embedder embedder) {
        this.embedder = embedder;
    }

    @Override
    public void ingest(String docId, String text) throws Exception {
        ids.add(docId);
        vecs.add(normalize(embedder.embed(text)));
        built = false; // 语料变了，下次 search 重建索引
    }

    @Override
    public List<ScoredDoc> search(String query, int topK) throws Exception {
        if (ids.isEmpty()) {
            return List.of();
        }
        if (!built) {
            build();
        }
        float[] q = normalize(embedder.embed(query));
        int k = Math.min(topK, ids.size());
        TopDocs top = searcher.search(new KnnVectorQuery(FIELD_VEC, q, k), k);
        List<ScoredDoc> res = new ArrayList<>(top.scoreDocs.length);
        for (ScoreDoc sd : top.scoreDocs) {
            String id = searcher.doc(sd.doc).get(FIELD_ID);
            res.add(new ScoredDoc(id, sd.score));
        }
        return res;
    }

    /** 把所有向量写成一个内存 Lucene 索引并打开 NRT 检索器（HNSW 图在 flush 时构建）。 */
    private void build() throws Exception {
        closeQuietly();
        directory = new ByteBuffersDirectory();
        writer = new IndexWriter(directory, new IndexWriterConfig());
        for (int i = 0; i < vecs.size(); i++) {
            Document d = new Document();
            d.add(new KnnVectorField(FIELD_VEC, vecs.get(i)));
            d.add(new StoredField(FIELD_ID, ids.get(i)));
            writer.addDocument(d);
        }
        reader = DirectoryReader.open(writer);
        searcher = new IndexSearcher(reader);
        built = true;
    }

    private void closeQuietly() {
        try {
            if (reader != null) {
                reader.close();
            }
            if (writer != null) {
                writer.close();
            }
            if (directory != null) {
                directory.close();
            }
        } catch (Exception ignore) {
            // 实验代码，关闭失败不影响结论
        }
    }

    private static float[] normalize(float[] v) {
        double s = 0;
        for (float x : v) {
            s += x * x;
        }
        double n = Math.sqrt(s);
        if (n == 0) {
            return v;
        }
        float[] out = new float[v.length];
        for (int i = 0; i < v.length; i++) {
            out[i] = (float) (v[i] / n);
        }
        return out;
    }
}
