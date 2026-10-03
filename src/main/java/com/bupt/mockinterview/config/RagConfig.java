package com.bupt.mockinterview.config;

import com.bupt.mockinterview.rag.DashscopeEmbedder;
import com.bupt.mockinterview.rag.DashscopeProperties;
import com.bupt.mockinterview.rag.DynamicEmbedder;
import com.bupt.mockinterview.rag.Embedder;
import com.bupt.mockinterview.rag.FakeEmbedder;
import com.bupt.mockinterview.rag.InMemoryVectorStore;
import com.bupt.mockinterview.rag.RagService;
import com.bupt.mockinterview.security.CurrentUserService;
import okhttp3.OkHttpClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RAG Bean 装配（T6 引入，T4 写的 rag 类此前没有进 Spring 容器）。
 *
 * ⚠️ 降级设计：DashScope 平台密钥不可用时退化为 {@link FakeEmbedder}
 * （本地词频哈希向量），保证未配置阶段整条链路能跑通。
 * R1-3 起 embedding key 只从 env（DASHSCOPE_API_KEY）读——embedding 永远走平台 key，
 * 不随用户 PLATFORM/BYOK 模式变化。维度固定 1024（实测确认）。
 */
@Configuration
public class RagConfig {

    private static final int DIM = 1024;

    @Bean
    public InMemoryVectorStore inMemoryVectorStore() {
        return new InMemoryVectorStore();
    }

    @Bean
    public Embedder embedder(OkHttpClient client, DashscopeProperties props) {
        DashscopeEmbedder real = new DashscopeEmbedder(client, props.getBaseUrl(),
                props::getApiKey, props.getEmbedModel());
        return new DynamicEmbedder(real, new FakeEmbedder(DIM), props::getApiKey);
    }

    @Bean
    public RagService ragService(Embedder embedder, InMemoryVectorStore store, CurrentUserService currentUser,
                                 com.bupt.mockinterview.billing.QuotaService quota,
                                 com.bupt.mockinterview.billing.QuotaProperties quotaProps) {
        return new RagService(embedder, store, currentUser, quota, quotaProps);
    }
}
