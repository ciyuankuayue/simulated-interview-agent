package com.bupt.mockinterview.rag;

import com.bupt.mockinterview.billing.QuotaExhaustedException;
import com.bupt.mockinterview.billing.QuotaProperties;
import com.bupt.mockinterview.billing.QuotaService;
import com.bupt.mockinterview.billing.UsageScene;
import com.bupt.mockinterview.domain.KeyMode;
import com.bupt.mockinterview.security.CurrentUserService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R2 批 3：{@link RagService} 的 embedding 计量分支纯单测（不起 Spring）。
 * 真实 token 实扣 / usage 缺失按字符估算 / Fake 向量(tokens=0)不扣 / 预检 402 / 无用户不计量。
 */
class RagServiceBillingTest {

    private final Embedder embedder = mock(Embedder.class);
    private final CurrentUserService currentUser = mock(CurrentUserService.class);
    private final QuotaService quota = mock(QuotaService.class);
    private final QuotaProperties props = new QuotaProperties(); // 默认开启估算

    private RagService svc() {
        when(embedder.name()).thenReturn("dashscope:test-model");
        return new RagService(embedder, new InMemoryVectorStore(), 100, 10, currentUser, quota, props);
    }

    @Test
    void anonymousUser_noBillingAtAll() throws Exception {
        when(currentUser.currentUserId()).thenReturn(null);
        when(embedder.embed(any())).thenReturn(new Embedding(new float[4], 9L, false));

        RagService svc = svc();
        svc.ingest("hello world", "note", "1");
        svc.retrieve("hello", 3);

        verify(quota, never()).ensureAvailable(anyLong(), any());
        verify(quota, never()).settlePlatformCall(anyLong(), any(), any(),
                anyLong(), anyLong(), anyLong(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    void ingest_preflightFails_neverEmbeds() throws Exception {
        when(currentUser.currentUserId()).thenReturn(7L);
        doThrow(QuotaExhaustedException.forMode(KeyMode.PLATFORM))
                .when(quota).ensureAvailable(7L, KeyMode.PLATFORM);

        RagService svc = svc();
        assertThatThrownBy(() -> svc.ingest("一些需要切块的资料内容", "note", "9"))
                .isInstanceOf(QuotaExhaustedException.class);
        verify(embedder, never()).embed(any());
        verify(quota, never()).settlePlatformCall(anyLong(), any(), any(),
                anyLong(), anyLong(), anyLong(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    void ingest_chargesPerChunk_withRealTokens() throws Exception {
        when(currentUser.currentUserId()).thenReturn(7L);
        // 260 字符 → 100 字符/块、overlap 10 → 3 块
        String text = "资".repeat(260);
        when(embedder.embed(any())).thenReturn(new Embedding(new float[4], 5L, false));

        svc().ingest(text, "note", "9");

        verify(quota, times(1)).ensureAvailable(7L, KeyMode.PLATFORM);
        verify(quota, times(3)).settlePlatformCall(eq(7L), eq(UsageScene.EMBED_INGEST),
                eq("dashscope:test-model"), eq(0L), eq(0L), eq(5L), eq(false));
    }

    @Test
    void retrieve_missingUsage_chargesEstimatedChars_whenEnabled() throws Exception {
        when(currentUser.currentUserId()).thenReturn(7L);
        when(embedder.embed(any())).thenReturn(new Embedding(new float[4], 0L, true));

        svc().retrieve("查询语句八个字", 3);

        // 估算 = 输入字符长度（「查询语句八个字」恰 7 字符）≥1
        verify(quota).settlePlatformCall(eq(7L), eq(UsageScene.EMBED_QUERY),
                eq("dashscope:test-model"), eq(0L), eq(0L), eq(7L), eq(true));
    }

    @Test
    void retrieve_fakeVectorZeroTokens_notCharged() throws Exception {
        when(currentUser.currentUserId()).thenReturn(7L);
        when(embedder.embed(any())).thenReturn(new Embedding(new float[4], 0L, false));

        svc().retrieve("anything", 3);

        verify(quota).ensureAvailable(7L, KeyMode.PLATFORM);
        verify(quota, never()).settlePlatformCall(anyLong(), any(), any(),
                anyLong(), anyLong(), anyLong(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    void retrieve_missingUsage_estimateDisabled_notCharged() throws Exception {
        props.setUsageEstimateEnabled(false);
        when(currentUser.currentUserId()).thenReturn(7L);
        when(embedder.embed(any())).thenReturn(new Embedding(new float[4], 0L, true));

        RagService svc = svc();
        assertThat(svc.retrieve("abc", 3)).isEmpty();

        verify(quota, never()).settlePlatformCall(anyLong(), any(), contains("dashscope"),
                anyLong(), anyLong(), anyLong(), org.mockito.ArgumentMatchers.anyBoolean());
    }
}
