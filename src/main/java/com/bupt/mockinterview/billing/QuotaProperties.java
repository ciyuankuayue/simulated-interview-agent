package com.bupt.mockinterview.billing;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 额度配置（R1-3），绑定 {@code app.quota.*}。
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.quota")
public class QuotaProperties {

    /** 新用户注册赠送点数。 */
    private long signupBonus = 1000L;

    /** BYOK 模式每次 DeepSeek 调用扣减的服务点数。 */
    private long byokChargePerCall = 1L;
}
