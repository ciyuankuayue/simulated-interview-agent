package com.bupt.mockinterview.billing;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;

@Data @Component @ConfigurationProperties(prefix = "app.platform-budget")
public class PlatformBudgetProperties {
    private BigDecimal dailyCny = new BigDecimal("100");
    private int maxOutputTokens = 32768;

    @jakarta.annotation.PostConstruct
    public void validate() {
        if (dailyCny == null || dailyCny.signum() < 0 || dailyCny.compareTo(new BigDecimal("1000000")) > 0)
            throw new IllegalStateException("平台日预算必须在0至1000000元之间");
        if (maxOutputTokens < 4096 || maxOutputTokens > 65536)
            throw new IllegalStateException("模型输出上限必须在4096至65536 token之间");
    }
}
