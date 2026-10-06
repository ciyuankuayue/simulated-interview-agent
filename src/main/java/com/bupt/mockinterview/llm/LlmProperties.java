package com.bupt.mockinterview.llm;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 绑定 application.yml 的 {@code deepseek.*}（NEVER 把密钥写死在代码里，统一环境变量注入）。
 *
 * 模型名已核实（官方 api-docs.deepseek.com 定价页）：
 * - {@code deepseek-flash} 为当前推荐名（= DeepSeek-V4.1-Flash）；旧名 {@code deepseek-v4-flash} 已下线，
 *   仍可调用但会被静默映射到 V4.1-Flash，故一律用 deepseek-flash。
 * - {@code deepseek-v4-pro} 为旗舰推理档，仅当 Flash 思考模式判不准时升级。
 */
@Data
@Component
@ConfigurationProperties(prefix = "deepseek")
public class LlmProperties {

    private String baseUrl = "https://api.deepseek.com/v1";
    private String apiKey = "";
    private String modelFlash = "deepseek-flash";
    private String modelReasoner = "deepseek-flash";
    private String modelPro = "deepseek-v4-pro";
    private long timeoutMs = 60_000;
    private int maxTokens = 8192;
    /**
     * Critic 报告专用预算。思考模式下 max_tokens 限制 reasoning+content 总生成量，
     * 默认 8192 会被 reasoning 挤爆导致 JSON 截断（字段名写到一半就被砍）。
     * DeepSeek V4.1 Flash 支持到 384K 输出，这里给 32768 留足 content 空间。
     */
    private int criticMaxTokens = 32768;
}
