package com.bupt.mockinterview.security;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 多租户配置（R2-T0）。前缀 {@code app.tenant}。
 * <p>
 * strict=true（prod profile）时，线程缺失 {@link TenantContext} 的任何持久化访问直接抛异常
 * fail-closed；非 strict（dev/test）缺失时回退受过滤的兜底租户 0L（不是 root）。
 */
@Data
@ConfigurationProperties(prefix = "app.tenant")
public class TenantProperties {

    /** 严格模式：缺租户上下文即拒绝（生产必须 true）。 */
    private boolean strict = false;

    public TenantProperties() {}

    public TenantProperties(boolean strict) {
        this.strict = strict;
    }
}
