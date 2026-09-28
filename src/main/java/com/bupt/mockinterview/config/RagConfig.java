package com.bupt.mockinterview.config;

import com.bupt.mockinterview.rag.DashscopeEmbedder;
import com.bupt.mockinterview.rag.DashscopeProperties;
import com.bupt.mockinterview.rag.DynamicEmbedder;
import com.bupt.mockinterview.rag.Embedder;
import com.bupt.mockinterview.rag.FakeEmbedder;
import com.bupt.mockinterview.rag.InMemoryVectorStore;
import com.bupt.mockinterview.rag.RagService;
import com.bupt.mockinterview.security.CurrentUserService;
import com.bupt.mockinterview.security.KeyVault;
import okhttp3.OkHttpClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RAG Bean 装配（T6 引入，T4 写的 rag 类此前没有进 Spring 容器）。
 *
 * ⚠️ 降级设计：DashScope 密钥不可用时退化为 {@link FakeEmbedder}
 * （本地词频哈希向量），保证未配置阶段整条链路能跑通；页面保险箱保存/解锁密钥后由
 * {@link DynamicEmbedder} 每次调用现判，<b>热切换真实通义，无需重启</b>（2026-09-19）。
 * 维度固定 1024（实测确认）。
 */
@Configuration
public class RagConfig {

    private static final int DIM = 1024;

    @Bean
    public InMemoryVectorStore inMemoryVectorStore() {
        return new InMemoryVectorStore();
    }

    @Bean
    public Embedder embedder(OkHttpClient client, DashscopeProperties props, KeyVault keyVault) {
        DashscopeEmbedder real = new DashscopeEmbedder(client, props.getBaseUrl(),
                keyVault::dashscopeKey, props.getEmbedModel());
        return new DynamicEmbedder(real, new FakeEmbedder(DIM), keyVault::dashscopeKey);
    }

    @Bean
    public RagService ragService(Embedder embedder, InMemoryVectorStore store, CurrentUserService currentUser) {
        return new RagService(embedder, store, currentUser);
    }
}
