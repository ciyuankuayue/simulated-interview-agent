package com.bupt.mockinterview.billing;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 额度配置（R2 token 计量），绑定 {@code app.quota.*}。
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.quota")
public class QuotaProperties {

    /** 固定换算：1 积分 = 500 token 当量，存量余额及流水不改单位。 */
    public static final long TOKENS_PER_POINT = 500L;

    /** 新用户注册赠送 token 额度。 */
    private long signupBonusTokens = 200L * TOKENS_PER_POINT;

    /** BYOK 模式每次 DeepSeek 对话调用扣减的服务费（token 当量）。 */
    private long byokChargePerCall = TOKENS_PER_POINT;

    /** New usage tariff: 10 real tokens = 1 internal token equivalent. */
    private int byokTokenDivisor = 10;

    @jakarta.annotation.PostConstruct
    public void validate() {
        if (signupBonusTokens < 0 || byokTokenDivisor < 1 || byokTokenDivisor > 1000)
            throw new IllegalStateException("注册赠额须非负，服务费token除数须在1至1000之间");
    }

    public static final String BILLING_VERSION = "2026-10-30-token-v1";

    /** 厂商响应缺 usage 时是否按字符估算兜底扣减（false=只记日志不扣）。 */
    private boolean usageEstimateEnabled = true;
}
