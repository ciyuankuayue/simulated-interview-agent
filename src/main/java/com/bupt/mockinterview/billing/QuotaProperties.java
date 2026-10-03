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

    /** 新用户注册赠送 token 额度。 */
    private long signupBonusTokens = 500_000L;

    /** BYOK 模式每次 DeepSeek 对话调用扣减的服务费（token 当量）。 */
    private long byokChargePerCall = 1L;

    /** 厂商响应缺 usage 时是否按字符估算兜底扣减（false=只记日志不扣）。 */
    private boolean usageEstimateEnabled = true;
}
