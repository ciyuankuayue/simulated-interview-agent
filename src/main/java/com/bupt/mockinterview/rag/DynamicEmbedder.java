package com.bupt.mockinterview.rag;

import java.util.function.Supplier;

/**
 * 按密钥有无在真实通义与离线 Fake 之间<b>每次调用</b>动态切换的 Embedder（2026-09-19）。
 *
 * <p>目的：用户在页面保存/解锁 DashScope 密钥后，向量化立即热生效，无需重启服务。
 * 密钥为空时退到 {@link FakeEmbedder}，保证未配置阶段整条链路仍能跑通（降级语义与
 * 原 RagConfig 启动期二选一一致，只是判定时机从「启动」改为「每次调用」）。
 *
 * <p>⚠️ 已知边界：Fake（词频哈希）与真实模型语义空间不同，若同一进程内先用 Fake 入库、
 * 配好密钥后又用真实向量检索，两者余弦不可比。向量存储本就是内存态、重启清零；
 * 配好密钥后重新上传资料即可（/api/health/deps 的 vectorChunks 可观测入库量）。
 */
public class DynamicEmbedder implements Embedder {

    private final DashscopeEmbedder real;
    private final FakeEmbedder fake;
    private final Supplier<String> keySupplier;

    public DynamicEmbedder(DashscopeEmbedder real, FakeEmbedder fake, Supplier<String> keySupplier) {
        this.real = real;
        this.fake = fake;
        this.keySupplier = keySupplier;
    }

    private Embedder current() {
        String k = keySupplier.get();
        return (k != null && !k.isBlank()) ? real : fake;
    }

    @Override
    public Embedding embed(String text) throws Exception {
        return current().embed(text);
    }

    @Override
    public String name() {
        return current().name();
    }
}
