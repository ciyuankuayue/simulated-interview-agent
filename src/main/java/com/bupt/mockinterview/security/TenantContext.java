package com.bupt.mockinterview.security;

/**
 * 当前线程的租户上下文（R2-T0 多租户，D1）。
 * <p>
 * 与 {@link UserContext} 平行但相互独立：MVP 下 tenantId 与 userId 同值，却是独立的概念
 * （B2B 多 seat 的唯一分离点）。请求入口 set、finally clear；持久层由
 * {@link CurrentTenantResolver} 读取本上下文决定 Hibernate {@code @TenantId} 分区谓词。
 * <p>
 * root 模式（{@link #runAsRoot(Runnable)}）是唯一跨租户特权入口，只允许 ADMIN 鉴权后的
 * service 使用；root 块硬规则见 docs/domain.md §9：块内每条查询必须显式带 tenantId/userId
 * 谓词（跨租户枚举走白名单），且禁止 persist（哨兵 {@code -1L} 永不落库）。
 */
public final class TenantContext {

    private record State(Long tenantId, boolean root) {}

    private static final ThreadLocal<State> HOLDER = new ThreadLocal<>();

    private TenantContext() {}

    /** 设置当前租户（普通租户模式）。传 null 等价于 {@link #clear()}。 */
    public static void set(Long tenantId) {
        if (tenantId == null) {
            HOLDER.remove();
        } else {
            HOLDER.set(new State(tenantId, false));
        }
    }

    public static void clear() {
        HOLDER.remove();
    }

    /** 当前租户 ID；root 模式或未设置时返回 null（由 resolver 决定哨兵/兜底/抛错）。 */
    public static Long getTenantId() {
        State state = HOLDER.get();
        return state == null ? null : state.tenantId();
    }

    /** 当前线程是否处于 root 特权模式。 */
    public static boolean isRoot() {
        State state = HOLDER.get();
        return state != null && state.root();
    }

    /**
     * 在 root 特权模式下执行动作：期间 resolver 返回哨兵 -1L；结束后（含异常）恢复调用前状态。
     * 调用方必须已经过 ADMIN 鉴权；块内禁止 persist。
     */
    public static void runAsRoot(Runnable action) {
        State previous = HOLDER.get();
        HOLDER.set(new State(null, true));
        try {
            action.run();
        } finally {
            if (previous == null) {
                HOLDER.remove();
            } else {
                HOLDER.set(previous);
            }
        }
    }
}
