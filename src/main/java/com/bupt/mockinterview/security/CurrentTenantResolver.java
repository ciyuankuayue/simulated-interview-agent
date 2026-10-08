package com.bupt.mockinterview.security;

import org.hibernate.context.spi.CurrentTenantIdentifierResolver;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

/**
 * Hibernate 分区多租户（D1）的当前租户解析器：把 {@link TenantContext} 桥接给 Hibernate 6。
 * <p>
 * 规则（docs/domain.md §9）：
 * <ul>
 *   <li>root 模式 → 返回哨兵 {@link #ROOT_TENANT_ID}（-1L），仅供 ADMIN 跨租户枚举/运维；</li>
 *   <li>有普通租户 → 返回该租户 id；</li>
 *   <li>上下文缺失：应用就绪后 strict 直接抛 {@link IllegalStateException} fail-closed；
 *       非 strict 回退兜底租户 {@link #FALLBACK_TENANT_ID}（0L，受过滤的普通租户，不是 root）。</li>
 * </ul>
 * {@link #isRoot(Long)} 仅对精确的 -1L 返回 true（HIGH-1：0L/null 必须 false）。
 * <p>
 * <b>启动期门控</b>：Spring Data 仓储 Bean 创建期会构造/校验派生查询，Hibernate 此时就会回调
 * 解析器，而启动阶段尚无请求租户上下文。故 strict 只在 {@link ApplicationReadyEvent} 之后
 * fail-closed；就绪前一律回退兜底租户，避免上下文起不来（就绪后无真实流量，无放行窗口）。
 */
@Component
public class CurrentTenantResolver
        implements CurrentTenantIdentifierResolver<Long>, ApplicationListener<ApplicationReadyEvent> {

    /** root 哨兵：唯一跨租户标识，禁止落库。不得改为 0L（HIGH-1）。 */
    public static final Long ROOT_TENANT_ID = -1L;

    /** 非严格模式下上下文缺失时的兜底租户：受 Hibernate 租户谓词过滤的普通租户。 */
    public static final Long FALLBACK_TENANT_ID = 0L;

    static final String MISSING_MESSAGE =
            "TENANT_CONTEXT_MISSING：当前线程无租户上下文（strict 模式禁止无租户持久化访问）";

    private final TenantProperties tenantProperties;

    /** 应用是否已完成启动；就绪前不 fail-closed（见类注释启动期门控）。 */
    private volatile boolean applicationReady = false;

    public CurrentTenantResolver(TenantProperties tenantProperties) {
        this.tenantProperties = tenantProperties;
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        applicationReady = true;
    }

    /** 测试辅助：手工构造的 resolver 直接进入「就绪后」语义；Spring Bean 由 ready 事件置位。 */
    void markApplicationReady() {
        this.applicationReady = true;
    }

    @Override
    public Long resolveCurrentTenantIdentifier() {
        if (TenantContext.isRoot()) {
            return ROOT_TENANT_ID;
        }
        Long tenantId = TenantContext.getTenantId();
        if (tenantId != null) {
            return tenantId;
        }
        if (tenantProperties.isStrict() && applicationReady) {
            // 不依赖 Hibernate 对 null 租户的默认行为——主动 fail-closed
            throw new IllegalStateException(MISSING_MESSAGE);
        }
        return FALLBACK_TENANT_ID;
    }

    /** 仅精确的哨兵 -1L 是 root；0L（兜底租户）与 null 都不是。 */
    @Override
    public boolean isRoot(Long tenantId) {
        return ROOT_TENANT_ID.equals(tenantId);
    }

    @Override
    public boolean validateExistingCurrentSessions() {
        return true;
    }
}
