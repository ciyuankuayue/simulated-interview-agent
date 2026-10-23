package com.bupt.mockinterview.agent;

import com.bupt.mockinterview.llm.LlmProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
        assertTrue(repaired.contains("\"v\""), "应补上未闭合字符串引号");
    }

    // ---------- R7：behaviorReview 行为维度 ----------

    @Test
    void behaviorReview正常解析且逐轮回填track() {
        String json = """
                {"matchScore": 66,
                 "perTurnReview": [
                   {"turnSeq":1,"question":"回表是什么","score":4,"track":"tech",
                    "explanation":"e","correction":"c","knowledgeSupplement":"k","exemplarAnswer":"x"},
                   {"turnSeq":2,"question":"讲一次冲突经历","score":6,"track":"behavior",
                    "explanation":"STAR 基本完整","correction":"结果没量化","knowledgeSupplement":"","exemplarAnswer":"STAR 骨架…"}
                 ],
                 "collapsedPoints": [],
                 "expressionIssues": [],
                 "overallNotes": "技术尚可，行为要补量化",
                 "reminders": [],
                 "behaviorReview": {"dimensions":{"STAR结构":1,"行动细节":2,"结果与反思":1,"表达与抗压":2},
                                    "summary":"协作表达不错，结果缺量化",
                                    "suggestions":["结果先说数字","冲突先讲诉求"]}}
                """;
        CriticService.Payload p = service.parse(json);
        assertEquals(66, p.matchScore());
        assertEquals("tech", p.perTurnReview().get(0).get("track"));
        assertEquals("behavior", p.perTurnReview().get(1).get("track"));
        assertTrue(p.behaviorReview() != null && p.behaviorReview().containsKey("dimensions"));
        assertEquals("协作表达不错，结果缺量化", p.behaviorReview().get("summary"));
        assertEquals(2, ((java.util.List<?>) p.behaviorReview().get("suggestions")).size());
    }

    @Test
    void 无behaviorReview键或显式null时为null且track兜底tech() {
        // 老 prompt 产出（无 behaviorReview、逐轮无 track）
        CriticService.Payload oldStyle = service.parse("""
                {"matchScore": 50,
                 "perTurnReview": [{"turnSeq":1,"question":"q","score":5,
                                    "explanation":"","correction":"","knowledgeSupplement":"","exemplarAnswer":""}],
                 "collapsedPoints": [], "expressionIssues": [],
                 "overallNotes": "n", "reminders": []}
                """);
        assertNull(oldStyle.behaviorReview(), "老报告无该键应为 null");
        assertEquals("tech", oldStyle.perTurnReview().get(0).get("track"), "逐轮缺 track 兜底 tech");

        CriticService.Payload explicitNull = service.parse("""
                {"matchScore": 50, "perTurnReview": [], "collapsedPoints": [],
                 "expressionIssues": [], "overallNotes": "n", "reminders": [],
                 "behaviorReview": null}
                """);
        assertNull(explicitNull.behaviorReview(), "纯技术场显式 null 应解析为 null");
    }

    @Test
    void behaviorReview被截断时修复不抛() {
        // 真机现场：summary 写到一半被砍（引号未闭 + 两层对象未闭合）
        String truncated = """
                {"matchScore": 70, "perTurnReview": [], "collapsedPoints": [],
                 "expressionIssues": [], "overallNotes": "x", "reminders": [],
                 "behaviorReview": {"dimensions": {"STAR结构": 2}, "summary": "行为表现整体不错但""";
        CriticService.Payload p = service.parse(truncated);
        assertTrue(p.behaviorReview() != null, "修复后 behaviorReview 对象应救回");
        assertTrue(p.behaviorReview().containsKey("dimensions"), "截断点之前的 dimensions 应保留");
    }

    // ---------- R7：逐轮 track 以编排器权威值（TurnBrief）为准 ----------

    private CriticService.TurnBrief brief(int seq, String track) {
        return new CriticService.TurnBrief(seq, "q" + seq, "a" + seq, seq, track);
    }

    private java.util.Map<String, Object> turn(int seq, String modelTrack) {
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("turnSeq", seq);
        m.put("question", "q" + seq);
        m.put("score", seq);
        m.put("track", modelTrack);
        m.put("explanation", "");
        m.put("correction", "");
        m.put("knowledgeSupplement", "");
        m.put("exemplarAnswer", "");
        return m;
    }

    @Test
    void 权威track覆盖模型按题面的误标() {
        // 真机复现：技术官模仿已问列表出了行为味的题，模型把 turn2 误标 behavior
        CriticService.Payload modelSaid = new CriticService.Payload(50,
                java.util.List.of(turn(1, "behavior"), turn(2, "behavior"), turn(3, "tech")),
                java.util.List.of(), java.util.List.of(), "n", java.util.List.of(),
                java.util.Map.of("summary", "s"));
        java.util.List<CriticService.TurnBrief> briefs = java.util.List.of(
                brief(1, "behavior"), brief(2, "tech"), brief(3, "tech"));

        CriticService.Payload fixed = service.applyAuthoritativeTracks(modelSaid, briefs);
        assertEquals("behavior", fixed.perTurnReview().get(0).get("track"));
        assertEquals("tech", fixed.perTurnReview().get(1).get("track"), "turn2 出题角色是技术官，必须改回 tech");
        assertEquals("tech", fixed.perTurnReview().get(2).get("track"));
        assertTrue(fixed.behaviorReview() != null, "本场含行为轮，behaviorReview 保留");
    }

    @Test
    void 纯技术场模型脑补behaviorReview时强制置空() {
        CriticService.Payload modelSaid = new CriticService.Payload(50,
                java.util.List.of(turn(1, "tech")),
                java.util.List.of(), java.util.List.of(), "n", java.util.List.of(),
                java.util.Map.of("summary", "模型在纯技术场脑补的行为块"));
        CriticService.Payload fixed = service.applyAuthoritativeTracks(modelSaid,
                java.util.List.of(brief(1, "tech")));
        assertNull(fixed.behaviorReview(), "无行为轮时 behaviorReview 必须为 null");
    }

    @Test
    void brief里没有的turnSeq不能进入报告() {
        CriticService.Payload modelSaid = new CriticService.Payload(50,
                java.util.List.of(turn(9, "behavior")),
                java.util.List.of(), java.util.List.of(), "n", java.util.List.of(), null);
        CriticService.Payload fixed = service.applyAuthoritativeTracks(modelSaid,
                java.util.List.of(brief(1, "tech")));
        assertTrue(fixed.perTurnReview().isEmpty(), "模型捏造的轮次不能进入报告");
    }

    @Test
    void 重复轮次只保留一次且原题评分赛道按实际轮次恢复() {
        java.util.Map<String, Object> first = turn(1, "behavior");
        first.put("question", "模型改写的题目");
        first.put("score", 8);
        first.put("explanation", "第一份复盘");
        java.util.Map<String, Object> duplicate = turn(1, "tech");
        duplicate.put("explanation", "重复复盘");
        CriticService.Payload modelSaid = new CriticService.Payload(50,
                java.util.List.of(turn(2, "tech"), first, duplicate, turn(99, "behavior")),
                java.util.List.of(), java.util.List.of(), "n", java.util.List.of(), null);

        CriticService.Payload fixed = service.applyAuthoritativeTracks(modelSaid,
                java.util.List.of(new CriticService.TurnBrief(1, "真实技术题", "a", 3, "tech"),
                        new CriticService.TurnBrief(2, "真实行为题", "a", 6, "behavior")));

        assertEquals(2, fixed.perTurnReview().size());
        assertEquals(1, fixed.perTurnReview().get(0).get("turnSeq"), "报告顺序遵循实际轮次");
        assertEquals("真实技术题", fixed.perTurnReview().get(0).get("question"));
        assertEquals(3, fixed.perTurnReview().get(0).get("score"));
        assertEquals("tech", fixed.perTurnReview().get(0).get("track"));
        assertEquals("第一份复盘", fixed.perTurnReview().get(0).get("explanation"));
        assertEquals("behavior", fixed.perTurnReview().get(1).get("track"));
        assertEquals(6, fixed.perTurnReview().get(1).get("score"));
    }

    @Test
    void 未评分及未作答轮次不能被模型补成正常低分() {
        CriticService.Payload modelSaid = service.parse("""
                {"matchScore":30,"perTurnReview":[
                  {"turnSeq":1,"score":0,"question":"模型补写"},
                  {"turnSeq":2,"score":8,"question":"模型补写"}],
                 "collapsedPoints":[],"expressionIssues":[],"overallNotes":"n","reminders":[]}
                """);
        CriticService.Payload fixed = service.applyAuthoritativeTracks(modelSaid,
                java.util.List.of(new CriticService.TurnBrief(1, "判分失败题", "已回答", null, "behavior"),
                        new CriticService.TurnBrief(2, "未作答题", null, null, "tech")));

        assertNull(fixed.perTurnReview().get(0).get("score"));
        assertNull(fixed.perTurnReview().get(1).get("score"));
        assertEquals("behavior", fixed.perTurnReview().get(0).get("track"));
        assertEquals("未作答题", fixed.perTurnReview().get(1).get("question"));
    }

    @Test
    void 模型空评分或缺评分解析为null而非零且非法轮次不冒充真实轮次() {
        CriticService.Payload parsed = service.parse("""
                {"perTurnReview":[{"turnSeq":1,"score":null},
                  {"turnSeq":2},{"turnSeq":"1","score":0},{"turnSeq":1.5,"score":0}]}
                """);
        assertNull(parsed.perTurnReview().get(0).get("score"));
        assertNull(parsed.perTurnReview().get(1).get("score"));
        assertNull(parsed.perTurnReview().get(2).get("turnSeq"));
        assertNull(parsed.perTurnReview().get(3).get("turnSeq"));
        CriticService.Payload fixed = service.applyAuthoritativeTracks(parsed,
                java.util.List.of(brief(1, "tech"), brief(2, "tech")));
        assertEquals(2, fixed.perTurnReview().size());
    }
}
