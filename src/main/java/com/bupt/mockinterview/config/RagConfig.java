package com.bupt.mockinterview.config;

import com.bupt.mockinterview.rag.DashscopeEmbedder;
import com.bupt.mockinterview.rag.DashscopeProperties;
import com.bupt.mockinterview.rag.DynamicEmbedder;
import com.bupt.mockinterview.rag.Embedder;
import com.bupt.mockinterview.rag.FakeEmbedder;
import com.bupt.mockinterview.rag.LuceneVectorStore;
import com.bupt.mockinterview.rag.RagProperties;
import com.bupt.mockinterview.rag.RagService;
import com.bupt.mockinterview.rag.VectorStore;
import com.bupt.mockinterview.security.CurrentUserService;
import okhttp3.OkHttpClient;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;

/**
 * RAG Bean 装配（T6 引入，T4 写的 rag 类此前没有进 Spring 容器）。
 *
 * <p>⚠️ 降级设计：DashScope 平台密钥不可用时退化为 {@link FakeEmbedder}
 * （本地词频哈希向量），保证未配置阶段整条链路能跑通。
 * R1-3 起 embedding key 只从 env（DASHSCOPE_API_KEY）读——embedding 永远走平台 key，
 * 不随用户 PLATFORM/BYOK 模式变化。
 *
 * <p>R4 起向量存储为 {@link LuceneVectorStore}（FSDirectory 持久化 + HNSW + 用户隔离），
 * 目录/维度/启动清理由 {@link RagProperties}（app.rag.*）控制。
 */
@Configuration
public class RagConfig {

    /**
     * 向量存储：Lucene HNSW。生产/默认 FSDirectory 落盘持久化；
     * {@code app.rag.ram-directory=true}（仅测试）用 ByteBuffersDirectory 内存目录，
     * 避免多个并存的 Spring 测试上下文争用同一 FSDirectory 的 write.lock。
     * 返回接口类型 {@link VectorStore}，业务层不感知具体实现。销毁走类上的
     * {@code @PreDestroy}；destroyMethod="" 关闭 Spring 对 close() 的默认推断，避免双重调用。
     */
    @Bean(destroyMethod = "")
    public VectorStore vectorStore(RagProperties ragProps) throws IOException {
        if (ragProps.isRamDirectory()) {
            return new LuceneVectorStore(new ByteBuffersDirectory(), ragProps.getDim());
        }
        return new LuceneVectorStore(ragProps.resolveIndexDir(), ragProps.getDim());
    }

    @Bean
    public Embedder embedder(OkHttpClient client, DashscopeProperties props, RagProperties ragProps) {
        DashscopeEmbedder real = new DashscopeEmbedder(client, props.getBaseUrl(),
                props::getApiKey, props.getEmbedModel());
        // Fake 降级向量维度与 Lucene 配置维度对齐，避免无 key 降级时维度不一致写不进索引。
        return new DynamicEmbedder(real, new FakeEmbedder(ragProps.getDim()), props::getApiKey);
    }

    @Bean
    public RagService ragService(Embedder embedder, VectorStore store, CurrentUserService currentUser,
                                 com.bupt.mockinterview.billing.QuotaService quota,
                                 com.bupt.mockinterview.billing.QuotaProperties quotaProps) {
        return new RagService(embedder, store, currentUser, quota, quotaProps);
    }
}
