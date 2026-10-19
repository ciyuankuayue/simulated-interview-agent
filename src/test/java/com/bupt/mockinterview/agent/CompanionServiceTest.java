package com.bupt.mockinterview.agent;

import com.bupt.mockinterview.billing.QuotaExhaustedException;
import com.bupt.mockinterview.domain.KeyMode;
import com.bupt.mockinterview.llm.LlmException;
import com.bupt.mockinterview.llm.LlmPort;
import com.bupt.mockinterview.llm.LlmReply;
import com.bupt.mockinterview.llm.chat.ModelRole;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CompanionServiceTest {
    private final LlmPort llm = mock(LlmPort.class);
    private final CompanionService companion = new CompanionService(llm);

    private void reply(String text) {
        when(llm.chat(eq(ModelRole.FAST), eq(false), anyList(), isNull(), eq(false)))
                .thenReturn(new LlmReply(text, null));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private List<List<Map<String, Object>>> captured(int calls) {
        ArgumentCaptor<List<Map<String, Object>>> input = (ArgumentCaptor) ArgumentCaptor.forClass(List.class);
        verify(llm, times(calls)).chat(eq(ModelRole.FAST), eq(false), input.capture(), isNull(), eq(false));
        return input.getAllValues();
    }

    @Test
    void fastReplyKeepsRecentContextWithoutLeakingAnotherUser() {
        reply("  先不急着复盘，刚才那场面试的失落可以慢慢说。  ");
        assertThat(companion.chat(1L, "A_PRIVATE 面试受挫"))
                .containsEntry("safe", false).containsEntry("reply", "先不急着复盘，刚才那场面试的失落可以慢慢说。");
        companion.chat(2L, "B_PRIVATE 明天紧张");
        companion.chat(1L, "不要建议，只想聊聊");
        var messages = captured(3);
        assertThat(messages.get(1).toString()).doesNotContain("A_PRIVATE");
        assertThat(messages.get(2).toString()).contains("A_PRIVATE", "不要建议，只想聊聊")
                .doesNotContain("B_PRIVATE");
        assertThat(messages.get(2).get(0)).containsEntry("role", "system");
        assertThat(messages.get(2).get(2)).containsEntry("role", "assistant");
    }

    @Test
    void historyIsBoundedAndResetOnlyAffectsOwner() {
        reply("回应");
        for (int i = 0; i < 10; i++) companion.chat(1L, "PRIVATE_A_" + i);
        companion.chat(2L, "PRIVATE_B");
        companion.reset(1L);
        companion.chat(1L, "重新开始");
        companion.chat(2L, "继续说");
        var messages = captured(13);
        assertThat(messages.get(9)).hasSize(18);
        assertThat(messages.get(9).toString()).doesNotContain("PRIVATE_A_0");
        assertThat(messages.get(11)).hasSize(2);
        assertThat(messages.get(12).toString()).contains("PRIVATE_B");
    }

    @Test
    void quotaFailurePropagatesAndRetryDoesNotRepeatFailedInput() {
        when(llm.chat(eq(ModelRole.FAST), eq(false), anyList(), isNull(), eq(false)))
                .thenThrow(QuotaExhaustedException.forMode(KeyMode.PLATFORM))
                .thenReturn(new LlmReply("这回接着说。", null));
        assertThatThrownBy(() -> companion.chat(1L, "FAILED_PRIVATE"))
                .isInstanceOf(QuotaExhaustedException.class).hasMessageContaining("13481264947");
        companion.chat(1L, "重试");
        assertThat(captured(2).get(1)).hasSize(2);
        assertThat(captured(2).get(1).toString()).doesNotContain("FAILED_PRIVATE");
    }

    @Test
    void blankOrMissingOutputIsAnErrorInsteadOfFakeComfort() {
        when(llm.chat(eq(ModelRole.FAST), eq(false), anyList(), isNull(), eq(false)))
                .thenReturn(new LlmReply(" ", null), null, new LlmReply("有效回应", null));
        assertThatThrownBy(() -> companion.chat(1L, "EMPTY_PRIVATE")).isInstanceOf(LlmException.class);
        assertThatThrownBy(() -> companion.chat(1L, "NULL_PRIVATE")).isInstanceOf(LlmException.class);
        companion.chat(1L, "再次尝试");
        assertThat(captured(3).get(2)).hasSize(2);
    }

    @Test
    void crisisGuardSkipsModelAndClearsEarlierConversation() {
        reply("回应");
        companion.chat(1L, "EARLIER_PRIVATE");
        var response = companion.chat(1L, "我想自伤");
        assertThat(response).containsEntry("safe", true);
        assertThat(response.get("reply").toString()).contains("信任的人", "当地急救", "安全");
        verify(llm, times(1)).chat(any(), anyBoolean(), anyList(), any(), anyBoolean());
        companion.chat(1L, "重新聊聊");
        assertThat(captured(2).get(1)).hasSize(2);
    }
}
