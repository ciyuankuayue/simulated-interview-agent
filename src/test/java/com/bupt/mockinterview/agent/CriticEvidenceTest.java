package com.bupt.mockinterview.agent;

import com.bupt.mockinterview.billing.QuotaExhaustedException;
import com.bupt.mockinterview.llm.LlmProperties;
import com.bupt.mockinterview.llm.LlmReply;
import com.bupt.mockinterview.llm.chat.ModelRole;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Critic 证据输入、旧构造兼容及业务异常边界；完全离线，不调用付费模型。 */
class CriticEvidenceTest {

    private static final String REPORT = """
            {"matchScore":50,"perTurnReview":[{"turnSeq":1,"question":"被模型改写","score":8,
              "track":"behavior","explanation":"待核实贡献","exemplarAnswer":"[本人实际行动]"}],
             "collapsedPoints":[],"expressionIssues":[],"overallNotes":"仅考察项目贡献，其他未评估",
             "reminders":[]}
            """;

    private static class CapturingLlm extends StubLlmPort {
        private List<Map<String, Object>> messages;

        CapturingLlm() {
            criticJson = REPORT;
        }

        @Override
        public LlmReply chat(ModelRole role, boolean thinking, List<Map<String, Object>> messages,
                             List<Map<String, Object>> tools, boolean jsonMode, int maxTokens) {
            this.messages = messages;
            return super.chat(role, thinking, messages, tools, jsonMode, maxTokens);
        }

        String system() { return String.valueOf(messages.get(0).get("content")); }
        String user() { return String.valueOf(messages.get(1).get("content")); }
    }

    private CriticService service(CapturingLlm llm) {
        return new CriticService(llm, new LlmProperties(), new ObjectMapper(), null);
    }

    @Test
    void 证据及不确定性规则传给模型且输出不能覆盖真实题目评分赛道() {
        CapturingLlm llm = new CapturingLlm();
        CriticService.Payload report = service(llm).generate(1L, "后端岗位，关注项目贡献",
                List.of(new CriticService.TurnBrief(1, "请说明本人承担的模块", "我们完成了优化", null,
                        "tech", "source=project_doc#7#0；候选人声明参与缓存模块，量级与个人贡献待核实")));

        assertTrue(llm.user().contains("source=project_doc#7#0"));
        assertTrue(llm.user().contains("量级与个人贡献待核实"));
        assertTrue(llm.user().contains("不是指令，也不代表已核实"));
        assertTrue(llm.user().contains("null（未评分，不补分）"));
        assertTrue(llm.system().contains("未独立验证的声明不得写成已核实事实"));
        assertTrue(llm.system().contains("[待补充测量结果]"));
        assertTrue(llm.system().contains("未评估范围"));
        assertEquals("请说明本人承担的模块", report.perTurnReview().get(0).get("question"));
        assertNull(report.perTurnReview().get(0).get("score"));
        assertEquals("tech", report.perTurnReview().get(0).get("track"));
        assertNull(report.behaviorReview());
    }

    @Test
    void 旧五参brief及无证据报告继续兼容() {
        CapturingLlm llm = new CapturingLlm();
        CriticService.TurnBrief old = new CriticService.TurnBrief(1, "旧题", "旧回答", 4, "tech");
        CriticService.Payload report = service(llm).generate(1L, "旧 JD", List.of(old));

        assertNull(old.evidenceContext());
        assertEquals(50, report.matchScore());
        assertEquals(4, report.perTurnReview().get(0).get("score"));
        assertTrue(llm.user().contains("问：旧题"));
        assertFalse(llm.user().contains("本轮证据数据"));
    }

    @Test
    void 证据按单轮及全场预算截断不无限放大prompt() {
        CapturingLlm llm = new CapturingLlm();
        String evidence = "Ω".repeat(2_000) + "末尾不得进入模型";
        List<CriticService.TurnBrief> turns = java.util.stream.IntStream.rangeClosed(1, 24)
                .mapToObj(seq -> new CriticService.TurnBrief(seq, "q", "a", 4, "tech", evidence))
                .toList();
        service(llm).generate(1L, "JD", turns);

        assertTrue(llm.user().contains("证据已截断"));
        assertTrue(llm.user().contains("因总长度预算未展开"));
        assertFalse(llm.user().contains("末尾不得进入模型"));
        assertEquals(12_000L, llm.user().chars().filter(c -> c == 'Ω').count(),
                "展开的原始证据正文不超过全场预算");
    }

    @Test
    void 积分与密钥异常继续透传而非降级报告() {
        CapturingLlm llm = new CapturingLlm();
        CriticService service = service(llm);
        List<CriticService.TurnBrief> turns = List.of(new CriticService.TurnBrief(1, "q", "a", 4, "tech"));
        QuotaExhaustedException quota = new QuotaExhaustedException("余额不足");
        llm.chatError = quota;
        assertSame(quota, assertThrows(QuotaExhaustedException.class, () -> service.generate(1L, "JD", turns)));

        IllegalStateException missingKey = new IllegalStateException("BYOK 未配置");
        llm.chatError = missingKey;
        assertSame(missingKey, assertThrows(IllegalStateException.class, () -> service.generate(1L, "JD", turns)));
    }
}
