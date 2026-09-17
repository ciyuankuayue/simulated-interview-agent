package com.bupt.mockinterview.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * T1 最小端点：验证工程可启动、web 层可用。
 * 后续资料管理 / 模拟 / SSE 接口在此包下扩展。
 */
@RestController
public class HealthController {

    @GetMapping("/api/health")
    public Map<String, String> health() {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("status", "UP");
        body.put("service", "mock-interview-agent");
        return body;
    }
}
