package com.bupt.mockinterview.agent;

import com.bupt.mockinterview.llm.LlmProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T8+/T9 收尾：Critic 报告 JSON 解析的健壮性。
 * 复现真机「max_tokens 不够 → JSON 被截断」的现场，验证：
 * 1) 正常 JSON 完整解析；
 * 2) 值被截断（缺闭合引号/缺闭合括号）能救回部分字段，不把原文塞进报告；
 * 3) 极端（键名被砍）或完全乱码时降级返回占位报告，绝不抛异常。
 */
class CriticParseTest {

    private final CriticService service = new CriticService(null, new LlmProperties(), new ObjectMapper(), null);

    @Test
    void 正常JSON完整解析() {
        String json = """
                {"matchScore": 15,
                 "perTurnReview": [{"turnSeq":1,"question":"什么是索引失效","score":3,
                                    "explanation":"没讲清最左前缀","correction":"应讲覆盖索引",
                                    "knowledgeSupplement":"可补 ICP","exemplarAnswer":"答：先看最左前缀"}],
                 "collapsedPoints": ["索引失效"],
                 "expressionIssues": [{"issue":"太啰嗦","guide":"精简"}],
                 "overallNotes": "整体注意 A","reminders": ["再看 B"]}
                """;
        CriticService.Payload p = service.parse(json);
        assertEquals(15, p.matchScore());
        assertEquals(1, p.perTurnReview().size());
        assertEquals("没讲清最左前缀", p.perTurnReview().get(0).get("explanation"));
        assertEquals("索引失效", p.collapsedPoints().get(0));
        assertEquals("整体注意 A", p.overallNotes());
        assertEquals("再看 B", p.reminders().get(0));
    }

    @Test
    void 值被截断补引号后能救回() {
        // 真机现场：explanation 的值写到一半被砍，连闭合引号都没有
        String truncated = "{\"matchScore\": 15, \"perTurnReview\": [{\"turnSeq\": 1, \"question\": \"什么是索引失效\", \"score\": 3, \"explanation\": \"被截断的讲解";
        CriticService.Payload p = service.parse(truncated);
        // 不降级：能解析出这一轮
        assertEquals(1, p.perTurnReview().size());
        assertEquals("被截断的讲解", p.perTurnReview().get(0).get("explanation"));
        // matchScore 在截断点之前已完整输出
        assertEquals(15, p.matchScore());
    }

    @Test
    void 缺闭合括号能补回() {
        String truncated = "{\"matchScore\": 15, \"perTurnReview\": [{\"turnSeq\": 1, \"question\": \"q\", \"score\": 0, \"explanation\": \"e\"";
        CriticService.Payload p = service.parse(truncated);
        assertEquals(1, p.perTurnReview().size());
        assertEquals("e", p.perTurnReview().get(0).get("explanation"));
    }

    @Test
    void 极端键名被砍或乱码时降级不抛() {
        // 复现用户贴出的「explanatio」键名写到一半——这种修不回来，靠抬高 max_tokens 规避，
        // 此处只保证：不抛异常、返回占位报告、不把原文塞进 overallNotes。
        String extreme = "{\"matchScore\": 15, \"perTurnReview\": [{\"turnSeq\": 1, \"question\": \"q\", \"score\": 0, \"explanatio";
        CriticService.Payload p = service.parse(extreme);
        assertTrue(p.perTurnReview().isEmpty(), "极端截断应降级为空，不塞脏数据");
        assertTrue(p.overallNotes().contains("解析失败"), "应给友好降级提示而非原始 JSON");

        // 完全乱码
        CriticService.Payload garbage = service.parse("这不是 json 啊啊啊");
        assertTrue(garbage.perTurnReview().isEmpty());
        assertFalse(garbage.overallNotes().contains("这不是 json"), "降级提示不应原样回显模型输出");
    }

    @Test
    void 修复器能补齐未闭合结构() {
        String repaired = CriticService.repairTruncatedJson("{\"a\": [{\"k\": \"v\"");
        assertTrue(repaired.endsWith("]}"), "应补齐缺失的 ] 与 }（且顺序正确：内层 } 先于 ]）");
        assertTrue(repaired.contains("\"v\""), "应补上未闭合的字符串引号");
    }
}
