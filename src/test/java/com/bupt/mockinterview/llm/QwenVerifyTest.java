package com.bupt.mockinterview.llm;

import com.bupt.mockinterview.llm.chat.ChatRequest;
import com.bupt.mockinterview.llm.chat.ChatResponse;
import com.bupt.mockinterview.llm.chat.QwenChatProvider;
import com.bupt.mockinterview.rag.DashscopeProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R3-2 真实联调：通义千问 OpenAI 兼容模式两条链路（key 恒为平台 DASHSCOPE_API_KEY）。
 *
 * 运行（本机 set 密钥后）：
 *   PowerShell:  $env:DASHSCOPE_API_KEY="sk-xxx"; .\mvnw.cmd test -Dtest=QwenVerifyTest
 * 未 set 密钥时整类自动跳过（Assumptions）。
 *
 * 【重点】score 链路必须先跑：enable_thinking=true 与 response_format=json_object 组合是 Qwen
 * 最易翻车点（部分模型报错或不遵守 JSON）；不过则回批 2 换 reasoner 模型/调参。
 * 模型名在 application.yml / DashscopeProperties 配置，以百炼控制台实测为准。
 */
public class QwenVerifyTest {

    @Test
    void verifyScoreThenFollowUp() throws Exception {
        String key = System.getenv("DASHSCOPE_API_KEY");
        Assumptions.assumeTrue(key != null && !key.isBlank(),
                "未设置 DASHSCOPE_API_KEY，跳过 Qwen 真实联调（本地 set 后跑）");

        DashscopeProperties props = new DashscopeProperties();
        props.setApiKey(key);
        QwenChatProvider qwen = new QwenChatProvider(new OkHttpClient(), new ObjectMapper(), props);

        // ---------- 链路 1（先跑）：判分 = 思考 + JSON 结构化输出 ----------
        String scoreSystem = """
                你是资深技术面试官，按候选人回答打分（0-8）。
                只输出 JSON，不要任何多余文字，不要 markdown 代码块。格式：
                {"score": 0到8的整数,"breakdown": {"技术准确性":"","深度":""},
                 "missed_points":[],"followup":"一句追问","knowledge_point":"知识点"}
                """;
        String scoreUser = "题目：MySQL InnoDB 的回表是什么？如何避免？\n"
                + "候选人回答：回表就是查完索引再查主键索引，可以用覆盖索引避免。";

        ChatRequest scoreReq = new ChatRequest(props.getChatModelReasoner(),
                List.of(Map.of("role", "system", "content", scoreSystem),
                        Map.of("role", "user", "content", scoreUser)),
                true, true, null, 8192);
        ChatResponse scoreResp = qwen.chat(scoreReq, key);

        assertFalse(scoreResp.content() == null || scoreResp.content().isBlank(),
                "思考+JSON 链路正文不应为空（为空多半是 max_tokens 被 reasoning 吃光）");
        JsonNode root = new ObjectMapper().readTree(scoreResp.content());
        int score = root.path("score").asInt(-1);
        System.out.println("[verify-qwen] reasoner=" + props.getChatModelReasoner()
                + " score=" + score + " raw=" + scoreResp.content());
        assertTrue(score >= 0 && score <= 8, "score 应在 0-8，实际 " + score);
        assertFalse(root.path("knowledge_point").asText("").isBlank(),
                "knowledge_point 不应缺失（错题本聚合依赖它）");
        assertTrue(scoreResp.usage().path("total_tokens").asLong() > 0,
                "usage.total_tokens 应存在且 >0（平台按它实扣）");

        // ---------- 链路 2：追问（非思考） ----------
        String followSystem = "你是 Java 后端技术深挖官。用户答得浅时只追问一句 why，不展开讲解。";
        ChatRequest followReq = new ChatRequest(props.getChatModelFast(),
                List.of(Map.of("role", "system", "content", followSystem),
                        Map.of("role", "user", "content", scoreUser)),
                false, false, null, 4096);
        ChatResponse followResp = qwen.chat(followReq, key);

        System.out.println("[verify-qwen] fast=" + props.getChatModelFast()
                + " followup=" + followResp.content());
        assertFalse(followResp.content() == null || followResp.content().isBlank(),
                "追问内容不应为空");
        assertTrue(followResp.reasoning() == null || followResp.reasoning().isBlank(),
                "enable_thinking=false 不应返回 reasoning_content");
        assertTrue(followResp.usage().path("total_tokens").asLong() > 0,
                "usage.total_tokens 应存在且 >0（平台按它实扣）");
    }
}
