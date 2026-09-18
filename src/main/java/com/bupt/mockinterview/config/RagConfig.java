package com.bupt.mockinterview.config;

import com.bupt.mockinterview.rag.DashscopeEmbedder;
import com.bupt.mockinterview.rag.DashscopeProperties;
import com.bupt.mockinterview.rag.Embedder;
import com.bupt.mockinterview.rag.FakeEmbedder;
import com.bupt.mockinterview.rag.InMemoryVectorStore;
import com.bupt.mockinterview.rag.RagService;
import okhttp3.OkHttpClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RAG Bean 装配（T6 引入，T4 写的 rag 类此前没有进 Spring 容器）。
 *
 * ⚠️ 降级设计：未 set {@code DASHSCOPE_API_KEY} 时注入 {@link FakeEmbedder}
 * （本地词频哈希向量），保证本地/测试环境能启动、能跑通编排；
 * set 了才用真实通义 embedding。维度固定 1024（实测确认）。
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
        if (!props.enabled()) {
            return new FakeEmbedder(DIM);
        }
        return new DashscopeEmbedder(client, props.getBaseUrl(), props.getApiKey(), props.getEmbedModel());
    }

    @Bean
    public RagService ragService(Embedder embedder, InMemoryVectorStore store) {
        return new RagService(embedder, store);
    }
}
