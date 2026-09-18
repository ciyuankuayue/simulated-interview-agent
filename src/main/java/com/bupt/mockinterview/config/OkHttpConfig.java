package com.bupt.mockinterview.config;

import okhttp3.ConnectionPool;
import okhttp3.OkHttpClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

/**
 * 全局唯一 OkHttpClient（决策 #13：单例 client + 单 ConnectionPool，不引 JDK HttpClient）。
 *
 * rag 的 DashscopeEmbedder 与 llm 的 LlmService 共用这一个实例，避免每处各建连接池。
 * LlmService 需要更长超时时用 {@code newBuilder()} 派生——它共享本实例的连接池与线程池。
 */
@Configuration
public class OkHttpConfig {

    @Bean
    public OkHttpClient okHttpClient() {
        return new OkHttpClient.Builder()
                .connectionPool(new ConnectionPool(20, 5, TimeUnit.MINUTES))
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .callTimeout(90, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build();
    }
}
