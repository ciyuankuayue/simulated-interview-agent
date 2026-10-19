package com.bupt.mockinterview.agent;

import com.bupt.mockinterview.billing.QuotaExhaustedException;
import com.bupt.mockinterview.llm.LlmException;
import com.bupt.mockinterview.llm.LlmPort;
import com.bupt.mockinterview.llm.LlmReply;
import com.bupt.mockinterview.llm.chat.ModelRole;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class StudyCoachServiceTest {
    private final LlmPort llm = mock(LlmPort.class);
    private final StudyCoachService coach = new StudyCoachService(llm);

    private void reply(String content) {
        when(llm.chat(eq(ModelRole.FAST), eq(false), anyList(), isNull(), eq(false)))
                .thenReturn(new LlmReply(content, null));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private List<List<Map<String, Object>>> captured(int calls) {
        ArgumentCaptor<List<Map<String, Object>>> captor = (ArgumentCaptor) ArgumentCaptor.forClass(List.class);
        verify(llm, times(calls)).chat(eq(ModelRole.FAST), eq(false), captor.capture(), isNull(), eq(false));
        return captor.getAllValues();
    }

    @Test
    void fastModelAndPromptDoNotInventPersonalContext() {
        reply("先弄清原理，再用一个例子验证。");
        assertThat(coach.chat(1L, "怎样理解隔离级别？")).containsEntry("reply", "先弄清原理，再用一个例子验证。");
        var messages = captured(1).get(0);
        assertThat(messages).hasSize(2);
        assertThat(messages.get(0).get("content").toString())
                .contains("通考小师", "不得编造", "没有自动读取资料库或报告", "不承诺永久记忆");
        assertThat(messages.get(1)).containsEntry("role", "user").containsEntry("content", "怎样理解隔离级别？");
    }

    @Test
    void usersAndWarmCompanionKeepIndependentHistories() {
        reply("回复");
        new CompanionService(llm).chat(1L, "WARM_PRIVATE");
        coach.chat(1L, "A_PRIVATE");
        coach.chat(2L, "B_PRIVATE");
        coach.chat(1L, "继续我的问题");
        var inputs = captured(4);
        assertThat(inputs.get(1).toString()).doesNotContain("WARM_PRIVATE");
        assertThat(inputs.get(2).toString()).doesNotContain("A_PRIVATE", "WARM_PRIVATE");
        assertThat(inputs.get(3).toString()).contains("A_PRIVATE").doesNotContain("B_PRIVATE", "WARM_PRIVATE");
    }

    @Test
    void historyIsBoundedAndResetOnlyClearsOwner() {
        reply("回答");
        for (int i = 0; i < 10; i++) coach.chat(1L, "A-question-" + i);
        coach.chat(2L, "B-original");
        coach.reset(1L);
        coach.chat(1L, "new-conversation");
        coach.chat(2L, "B-followup");
        var inputs = captured(13);
        assertThat(inputs.get(9)).hasSize(18);
        assertThat(inputs.get(9).toString()).doesNotContain("A-question-0");
        assertThat(inputs.get(11)).hasSize(2);
        assertThat(inputs.get(12).toString()).contains("B-original");
    }

    @Test
    void quotaFailureIsPropagatedAndDoesNotCommitFailedInput() {
        when(llm.chat(eq(ModelRole.FAST), eq(false), anyList(), isNull(), eq(false)))
                .thenThrow(new QuotaExhaustedException("额度不足"))
                .thenReturn(new LlmReply("重试成功", null));
        assertThatThrownBy(() -> coach.chat(1L, "FAILED_PRIVATE")).isInstanceOf(QuotaExhaustedException.class);
        coach.chat(1L, "新问题");
        assertThat(captured(2).get(1)).hasSize(2);
        assertThat(captured(2).get(1).toString()).doesNotContain("FAILED_PRIVATE");
    }

    @Test
    void emptyOutputIsAnErrorAndBusyGuardIsReleased() {
        when(llm.chat(eq(ModelRole.FAST), eq(false), anyList(), isNull(), eq(false)))
                .thenReturn(new LlmReply(" ", null), new LlmReply("有效回答", null));
        assertThatThrownBy(() -> coach.chat(1L, "空输出问题")).isInstanceOf(LlmException.class);
        assertThat(coach.chat(1L, "下一次")).containsEntry("reply", "有效回答");
        assertThat(captured(2).get(1)).hasSize(2);
    }

    @Test
    void concurrentRequestRejectedAndResetDoesNotRestoreLateHistory() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        when(llm.chat(eq(ModelRole.FAST), eq(false), anyList(), isNull(), eq(false))).thenAnswer(invocation -> {
            List<Map<String, Object>> messages = invocation.getArgument(2);
            if (messages.toString().contains("SLOW_PRIVATE")) {
                entered.countDown();
                if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("test release timed out");
            }
            return new LlmReply("回答", null);
        });
        var executor = Executors.newSingleThreadExecutor();
        try {
            var first = executor.submit(() -> coach.chat(1L, "SLOW_PRIVATE"));
            assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> coach.chat(1L, "duplicate")).isInstanceOf(IllegalStateException.class);
            coach.reset(1L);
            release.countDown();
            first.get(5, TimeUnit.SECONDS);
            coach.chat(1L, "after-reset");
            assertThat(captured(2).get(1).toString()).doesNotContain("SLOW_PRIVATE");
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }
}
