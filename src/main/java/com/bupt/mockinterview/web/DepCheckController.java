package com.bupt.mockinterview.web;

import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * T2 验收辅助：探测 MySQL / Redis 连通性。
 * 不建 Entity、不写业务，仅供 `.\mvnw.cmd spring-boot:run` 后一键确认依赖就绪。
 * 路径：GET /api/health/deps
 */
@RestController
public class DepCheckController {

    private final JdbcTemplate jdbcTemplate;
    private final RedisConnectionFactory redisConnectionFactory;

    public DepCheckController(JdbcTemplate jdbcTemplate, RedisConnectionFactory redisConnectionFactory) {
        this.jdbcTemplate = jdbcTemplate;
        this.redisConnectionFactory = redisConnectionFactory;
    }

    @GetMapping("/api/health/deps")
    public Map<String, Object> deps() {
        Map<String, Object> result = new LinkedHashMap<>();

        // 数据源探活（h2 profile 下是 H2，默认 profile 下是 MySQL）
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

        return result;
    }
}
