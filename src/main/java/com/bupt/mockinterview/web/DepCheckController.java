package com.bupt.mockinterview.web;

import com.bupt.mockinterview.llm.LlmProperties;
import com.bupt.mockinterview.rag.Embedder;
import com.bupt.mockinterview.rag.VectorStore;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 验收辅助：探测 MySQL / Redis 连通性 + RAG 嵌入模型是否真实生效。
 * 不写业务，仅供启动后一键确认环境就绪。
 * 路径：GET /api/health/deps
 */
@RestController
public class DepCheckController {

    /** embedding ping 结果缓存时长（毫秒）：避免频繁刷新探活页反复消耗 embedding 额度。 */
    private static final long PING_CACHE_MS = 30_000L;

    private final JdbcTemplate jdbcTemplate;
    private final RedisConnectionFactory redisConnectionFactory;
    private final Embedder embedder;
    private final VectorStore vectorStore;
    private final LlmProperties llmProps;

    private volatile long pingAt;
    private volatile Map<String, Object> pingCache;

    public DepCheckController(JdbcTemplate jdbcTemplate, RedisConnectionFactory redisConnectionFactory,
                              Embedder embedder, VectorStore vectorStore, LlmProperties llmProps) {
        this.jdbcTemplate = jdbcTemplate;
        this.redisConnectionFactory = redisConnectionFactory;
        this.embedder = embedder;
        this.vectorStore = vectorStore;
        this.llmProps = llmProps;
    }

    @GetMapping("/api/health/deps")
    public Map<String, Object> deps() {
        Map<String, Object> result = new LinkedHashMap<>();

        // 数据源探活（默认 profile 是 H2，mysql profile 是 MySQL 8）
        try {
            Integer v = jdbcTemplate.queryForObject("select 1", Integer.class);
            result.put("database", Map.of("ok", v != null && v == 1, "detail", "connected"));
        } catch (Exception e) {
            result.put("database", Map.of("ok", false, "detail", e.getMessage()));
        }

        // Redis：ping 探活（lettuce 懒连接，首次调用才真正连）
        try {
            redisConnectionFactory.getConnection().ping();
            result.put("redis", Map.of("ok", true, "detail", "connected"));
        } catch (Exception e) {
            result.put("redis", Map.of("ok", false, "detail", e.getMessage()));
        }

        result.put("deepseek", deepseekBlock());
        result.put("embedder", embedderBlock());
        return result;
    }

    /**
     * DeepSeek 平台密钥状态（不做真实 chat 探活——会烧 token；调用时失败自有重试/降级）。
     * R1-3 起只反映平台 env key；用户 BYOK key 不在此暴露（见 GET /api/user/api-key）。
     */
    private Map<String, Object> deepseekBlock() {
        boolean configured = llmProps.getApiKey() != null && !llmProps.getApiKey().isBlank();
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("source", "ENV");
        block.put("configured", configured);
        return block;
    }

    /**
     * 嵌入模型状态：provider 标识（dashscope:&lt;model&gt; 为真实通义；FakeEmbedder 为降级）+
     * 全局存活向量索引 doc 总数（R4 起 Lucene 持久化：跨用户；不含已逻辑删除待合并的旧 replace
     * 版本；仅作粗监控，不是"当前用户块数"）+ 一次真实 embed ping 的维度结果。
     */
    private Map<String, Object> embedderBlock() {
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("provider", embedder.name());
        block.put("vectorChunks", vectorStore.size());
        block.put("ping", embeddingPing());
        return block;
    }

    private synchronized Map<String, Object> embeddingPing() {
        long now = System.currentTimeMillis();
        if (pingCache != null && now - pingAt < PING_CACHE_MS) {
            return pingCache;
        }
        try {
            float[] v = embedder.embed("连通性测试").vector();
            Map<String, Object> ok = new LinkedHashMap<>();
            ok.put("ok", true);
            ok.put("dim", v.length);
            ok.put("detail", "embedding call succeeded");
            pingCache = Map.copyOf(ok);
        } catch (Exception e) {
            Map<String, Object> bad = new LinkedHashMap<>();
            bad.put("ok", false);
            bad.put("detail", String.valueOf(e.getMessage()));
            pingCache = Map.copyOf(bad);
        }
        pingAt = now;
        return pingCache;
    }
}
