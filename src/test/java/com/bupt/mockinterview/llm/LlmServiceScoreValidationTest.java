package com.bupt.mockinterview.llm;

import com.bupt.mockinterview.billing.QuotaProperties;
import com.bupt.mockinterview.billing.QuotaService;
import com.bupt.mockinterview.domain.KeyMode;
import com.bupt.mockinterview.llm.chat.ChatProvider;
import com.bupt.mockinterview.llm.chat.ChatRequest;
import com.bupt.mockinterview.llm.chat.ChatResponse;
import com.bupt.mockinterview.llm.chat.ModelRouter;
import com.bupt.mockinterview.security.CurrentUserService;
import com.bupt.mockinterview.security.KeyResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LlmServiceScoreValidationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final ChatProvider provider = mock(ChatProvider.class);
    private LlmService llm;

    @BeforeEach
    void setUp() {
        LlmProperties props = new LlmProperties();
        when(provider.id()).thenReturn("deepseek");
        KeyResolver keys = mock(KeyResolver.class);
        when(keys.resolveForChat(null, "deepseek"))
                .thenReturn(new KeyResolver.ResolvedKey("offline-key", KeyMode.PLATFORM));
        llm = new LlmService(new OkHttpClient(), MAPPER, props,
                new ModelRouter(List.of(provider), props), keys, mock(CurrentUserService.class),
                mock(QuotaService.class), new QuotaProperties());
    }

    private static ObjectNode techScore() {
        ObjectNode root = MAPPER.createObjectNode().put("score", 6);
        root.putObject("breakdown").put("正确性", 2).put("完整性", 2).put("深度", 1).put("实证", 1);
        root.putArray("missed_points").add("缺少实测过程");
        root.put("knowledge_point", "索引优化的实测依据").put("followup", "你如何测量？");
        return root;
    }

    private void reply(String content) {
        when(provider.chat(any(), anyString())).thenReturn(new ChatResponse(content, null, null));
    }

    @Test
    void techScore_acceptsFourDimensionsAndPassesReferenceEvidenceAsData() {
        reply(techScore().toString());

        ScoreResult score = llm.score(ScoreTrack.TECH, "如何验证索引收益？", "通过执行计划和耗时", "资料记录：订单项目，未记载具体指标");

        assertThat(score.score()).isEqualTo(6);
        assertThat(score.breakdown()).containsExactly(
                Map.entry("正确性", 2), Map.entry("完整性", 2), Map.entry("深度", 1), Map.entry("实证", 1));
        assertThat(score.missedPoints()).containsExactly("缺少实测过程");
        ArgumentCaptor<ChatRequest> request = ArgumentCaptor.forClass(ChatRequest.class);
        verify(provider).chat(request.capture(), eq("offline-key"));
        assertThat(request.getValue().thinking()).isTrue();
        assertThat(request.getValue().jsonMode()).isTrue();
        assertThat(request.getValue().messages().get(0).get("content").toString()).contains("仅为数据", "不能冒充本轮");
        assertThat(request.getValue().messages().get(1).get("content").toString())
                .contains("如何验证索引收益？", "通过执行计划和耗时", "<reference_evidence>", "未记载具体指标");
    }

    @Test
    void behaviorScore_usesAuthoritativeBehaviorDimensions() {
        ObjectNode root = MAPPER.createObjectNode().put("score", 4);
        root.putObject("breakdown").put("STAR结构", 1).put("行动细节", 1).put("结果与反思", 1).put("表达与抗压", 1);
        reply(root.toString());

        ScoreResult score = llm.score(ScoreTrack.BEHAVIOR, "说一次冲突", "我先沟通");

        assertThat(score.score()).isEqualTo(4);
        assertThat(score.breakdown()).containsOnlyKeys("STAR结构", "行动细节", "结果与反思", "表达与抗压");
    }

    @Test
    void behaviorScore_rejectsTechnicalRubricEvenWhenTotalMatches() {
        reply(techScore().toString());
        assertThatThrownBy(() -> llm.score(ScoreTrack.BEHAVIOR, "说一次冲突", "我先沟通"))
                .isInstanceOf(LlmException.class).hasMessageContaining("STAR结构");
    }

    @Test
    void fencedJson_remainsCompatible() {
        reply("```json\n" + techScore() + "\n```");
        assertThat(llm.score(ScoreTrack.TECH, "问题", "回答").score()).isEqualTo(6);
    }

    static Stream<String> invalidScores() {
        List<String> invalid = new ArrayList<>(List.of("[]", "null", "{}", "not-json"));
        invalid.add(techScore().toString() + " {}");
        for (String value : List.of("\"6\"", "6.0", "true", "null", "-1", "9", "9223372036854775807")) {
            ObjectNode root = techScore();
            try {
                root.set("score", MAPPER.readTree(value));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            invalid.add(root.toString());
        }
        ObjectNode missingScore = techScore();
        missingScore.remove("score");
        invalid.add(missingScore.toString());
        ObjectNode missingBreakdown = techScore();
        missingBreakdown.remove("breakdown");
        invalid.add(missingBreakdown.toString());
        ObjectNode absentDimension = techScore();
        ((ObjectNode) absentDimension.path("breakdown")).remove("实证");
        invalid.add(absentDimension.toString());
        ObjectNode extraDimension = techScore();
        ((ObjectNode) extraDimension.path("breakdown")).put("模型自创维度", 0);
        invalid.add(extraDimension.toString());
        for (String value : List.of("\"1\"", "1.5", "true", "null", "-1", "3", "9223372036854775807")) {
            ObjectNode root = techScore();
            try {
                ((ObjectNode) root.path("breakdown")).set("实证", MAPPER.readTree(value));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            invalid.add(root.toString());
        }
        invalid.add(techScore().put("score", 5).toString());
        return invalid.stream();
    }

    @ParameterizedTest
    @MethodSource("invalidScores")
    void invalidScore_isRejectedInsteadOfInventingLowScore(String json) {
        reply(json);
        assertThatThrownBy(() -> llm.score(ScoreTrack.TECH, "问题", "回答"))
                .isInstanceOf(LlmException.class);
    }

    @Test
    void toolOnlyReply_cannotMasqueradeAsFinalScore() {
        when(provider.chat(any(), anyString())).thenReturn(new ChatResponse("", null, null,
                List.of(new ToolCall("call-1", "read_history", "{}"))));
        assertThatThrownBy(() -> llm.score(ScoreTrack.TECH, "问题", "回答"))
                .isInstanceOf(LlmException.class);
    }
}
