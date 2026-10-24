package com.bupt.mockinterview.agent;

import com.bupt.mockinterview.llm.LlmPort;
import com.bupt.mockinterview.llm.LlmReply;
import com.bupt.mockinterview.memory.BuddyMemoryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BuddyChatRuntimeTest {
    private final LlmPort llm = mock(LlmPort.class);
    private final BuddyMemoryService memory = mock(BuddyMemoryService.class);
    private final BuddyChatRuntime runtime = new BuddyChatRuntime(llm, memory, new ObjectMapper());

    private void memory(boolean enabled) {
        when(memory.context(anyLong(), anyString())).thenReturn(new BuddyMemoryService.Context(enabled, 7, enabled ? "[]" : ""));
    }

    @Test
    void streamingHidesFooterAcrossEveryPossibleChunkBoundary() {
        String text = "你好，先举例说明。\n<buddy_memory>[{\"key\":\"explanation_style\",\"value\":\"先举例\",\"evidence\":\"我喜欢先举例\"}]</buddy_memory>";
        for (int split = 0; split <= text.length(); split++) {
            StringBuilder shown = new StringBuilder();
            BuddyReplyFilter filter = new BuddyReplyFilter(shown::append);
            filter.accept(text.substring(0, split));
            filter.accept(text.substring(split));
            filter.finish();
            assertThat(shown.toString()).isEqualTo("你好，先举例说明。\n");
        }
    }

    @Test
    void onlyGroundedCandidatesFromSuccessfulCurrentMessageCanBeSaved() {
        memory(true);
        String text = "我们先看一个例子。<buddy_memory>[{\"key\":\"explanation_style\",\"value\":\"先举例\",\"evidence\":\"我喜欢先举例\"},"
                + "{\"key\":\"target_role\",\"value\":\"资深架构师\",\"evidence\":\"我喜欢\"}]</buddy_memory>";
        when(llm.chat(any(), anyBoolean(), anyList(), isNull(), eq(false))).thenReturn(new LlmReply(text, null));
        var result = runtime.chat(1L, "coach", "SYSTEM", "我喜欢先举例", null);
        assertThat(result.get("reply")).isEqualTo("我们先看一个例子。");
        verify(memory).applyCandidates(1L, "coach", 7L,
                List.of(new BuddyMemoryService.Candidate("explanation_style", "先举例", "我喜欢先举例")));
        runtime.chat(1L, "coach", "SYSTEM", "这是别人的话，不要记住", null);
        verify(memory, times(1)).applyCandidates(anyLong(), anyString(), anyLong(), anyList());
    }

    @Test
    void disabledMemoryDoesNotAddProtocolOrSaveCandidates() {
        memory(false);
        doAnswer(invocation -> {
            assertThat(invocation.getArgument(2).toString()).doesNotContain("<buddy_memory>");
            return new LlmReply("回复", null);
        }).when(llm).chat(any(), anyBoolean(), anyList(), isNull(), eq(false));
        runtime.chat(1L, "coach", "SYSTEM", "我喜欢先举例", null);
        verify(memory, never()).applyCandidates(anyLong(), anyString(), anyLong(), anyList());
    }

    @Test
    void explicitPreferenceStillSavesWhenModelOmitsOrTruncatesMetadata() {
        memory(true);
        when(llm.chat(any(), anyBoolean(), anyList(), isNull(), eq(false)))
                .thenReturn(new LlmReply("好，先听你说。", null));
        runtime.chat(1L, "companion", "SYSTEM", "以后我难过时请先听我说，别急着给建议。", null);
        verify(memory).applyCandidates(eq(1L), eq("companion"), eq(7L), argThat(candidates ->
                candidates.size() == 1 && candidates.get(0).value().equals("先听我说，别急着给建议")));
    }

    @Test
    void sessionPreferencesOutliveRollingHistoryAndCanBeReversed() {
        memory(false);
        when(llm.chat(any(), anyBoolean(), anyList(), isNull(), eq(false))).thenReturn(new LlmReply("回答", null));
        runtime.chat(1L, "coach", "SYSTEM", "今天不测", null);
        for (int i = 0; i < 9; i++) runtime.chat(1L, "coach", "SYSTEM", "继续解释" + i, null);
        doAnswer(invocation -> {
            List<Map<String, Object>> messages = invocation.getArgument(2);
            assertThat(messages.get(0).get("content").toString()).contains("不要出自测题");
            return new LlmReply("回答", null);
        }).when(llm).chat(any(), anyBoolean(), anyList(), isNull(), eq(false));
        runtime.chat(1L, "coach", "SYSTEM", "再讲一点", null);
        doAnswer(invocation -> {
            List<Map<String, Object>> messages = invocation.getArgument(2);
            assertThat(messages.get(0).get("content").toString()).doesNotContain("不要出自测题");
            return new LlmReply("回答", null);
        }).when(llm).chat(any(), anyBoolean(), anyList(), isNull(), eq(false));
        runtime.chat(1L, "coach", "SYSTEM", "考考我", null);
    }

    @Test
    @SuppressWarnings("unchecked")
    void failedStreamDoesNotSaveItsPartialProseOrMemory() {
        memory(true);
        when(llm.streamChat(any(), anyBoolean(), anyList(), any())).thenAnswer(invocation -> {
            Consumer<String> output = invocation.getArgument(3);
            output.accept("说到一半");
            throw new com.bupt.mockinterview.llm.LlmException("连接断开");
        });
        assertThatThrownBy(() -> runtime.chat(1L, "coach", "SYSTEM", "FAILED_PRIVATE", ignored -> {}))
                .isInstanceOf(com.bupt.mockinterview.llm.LlmException.class);
        when(llm.chat(any(), anyBoolean(), anyList(), isNull(), eq(false))).thenAnswer(invocation -> {
            assertThat(invocation.getArgument(2).toString()).doesNotContain("FAILED_PRIVATE", "说到一半");
            return new LlmReply("完整回复", null);
        });
        runtime.chat(1L, "coach", "SYSTEM", "下一句", null);
        verify(memory, never()).applyCandidates(anyLong(), anyString(), anyLong(), anyList());
    }

    @Test
    void negationAndAttributedMentionsDoNotForcePersonalCrisisTemplate() {
        assertThat(CompanionService.needsImmediateSupport("我没有想自杀，只是面试有点紧张")).isFalse();
        assertThat(CompanionService.needsImmediateSupport("我没有不想活的意思，只是面试有点紧张")).isFalse();
        assertThat(CompanionService.needsImmediateSupport("朋友说他不想活了，我该怎么办")).isFalse();
        assertThat(CompanionService.needsImmediateSupport("我的朋友说他不想活了，我该怎么办")).isFalse();
        assertThat(CompanionService.needsImmediateSupport("最近失眠，按医嘱吃安眠药")).isFalse();
        assertThat(CompanionService.needsImmediateSupport("电影台词说‘我想死’，让我有点难过")).isFalse();
        assertThat(CompanionService.needsImmediateSupport("电影台词说‘我现在准备自杀’，让我有点难过")).isFalse();
        assertThat(CompanionService.needsImmediateSupport("我想自伤")).isTrue();
        assertThat(CompanionService.needsImmediateSupport("我现在准备自杀")).isTrue();
        assertThat(CompanionService.needsImmediateSupport("“我想死”就是我现在的感受")).isTrue();
        assertThat(CompanionService.needsImmediateSupport("我没有想自杀，但我现在准备割腕")).isTrue();
        assertThat(CompanionService.needsImmediateSupport("面试社死了，想找地方躲一会儿")).isFalse();
    }
}
