package com.bupt.mockinterview.llm;

import com.bupt.mockinterview.rag.DashscopeProperties;
import com.bupt.mockinterview.security.KeyVault;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T5 真实联调：验证 DeepSeek 两条链路 ——「追问生成（非思考）」与「判分 JSON（思考 + 结构化输出）」。
 *
 * 运行（需本机 set 密钥，沙盒无限流不跑）：
 *   PowerShell:  $env:DEEPSEEK_API_KEY="sk-xxx"; .\mvnw.cmd test -Dtest=DeepSeekVerifyTest
 *   Git Bash:    DEEPSEEK_API_KEY=sk-xxx ./mvnw.cmd test -Dtest=DeepSeekVerifyTest
 * 未 set 密钥时整类自动跳过（Assumptions）。
 */
public class DeepSeekVerifyTest {

    @Test
    void verifyFollowUpAndScore() throws Exception {
        String key = System.getenv("DEEPSEEK_API_KEY");
        Assumptions.assumeTrue(key != null && !key.isBlank(),
                "未设置 DEEPSEEK_API_KEY，跳过真实 LLM 联调（本地 set 后跑）");

        LlmProperties props = new LlmProperties();
        props.setApiKey(key); // baseUrl / model 用默认值（deepseek-flash）

        // KeyVault 的 env 分支直接返回 props 里的 key；保险箱目录给临时路径，不落用户目录
        KeyVault vault = new KeyVault(props, new DashscopeProperties(),
                Files.createTempDirectory("vault-verify").toString());
        LlmService llm = new LlmService(new OkHttpClient(), new ObjectMapper(), props, vault);

        // ---------- 链路 1：追问生成（非思考模式） ----------
        String system = "你是 Java 后端技术深挖官。用户答得浅时只追问一句 why，不展开讲解。";
        String user = "题目：synchronized 和 ReentrantLock 有什么区别？\n"
                + "候选人回答：synchronized 是关键字，ReentrantLock 是类，ReentrantLock 功能更多一些。";
        LlmReply reply = llm.followUp(system, user);

        System.out.println("[verify] 追问（非思考）-> " + reply.content());
        assertFalse(reply.content().isBlank(), "追问内容不应为空");
        assertTrue(reply.reasoningContent() == null || reply.reasoningContent().isBlank(),
                "非思考模式不应返回思维链（有则说明 thinking 未关掉）");

        // ---------- 链路 2：判分（思考模式 + JSON 输出） ----------
        String question = "MySQL InnoDB 的回表是什么？如何避免？";
        String answer = "回表就是查完索引再查主键索引，可以用覆盖索引避免。";
        ScoreResult r = llm.score(question, answer);

        System.out.println("[verify] 判分 score = " + r.score());
        System.out.println("[verify] breakdown   = " + r.breakdown());
        System.out.println("[verify] missed      = " + r.missedPoints());
        System.out.println("[verify] followup    = " + r.followup());
        System.out.println("[verify] knowledge_point = " + r.knowledgePointOrEmpty());
        System.out.println("[verify] 追问层数=" + r.followupDepth() + "，记错题本=" + r.needWeakPoint());

        assertTrue(r.score() >= 0 && r.score() <= 8, "score 应在 0-8，实际 " + r.score());
        assertFalse(r.breakdown().isEmpty(), "breakdown 四维不应为空");
        assertFalse(r.missedPoints().isEmpty(), "该回答较简略，missed_points 不应为空");
        assertFalse(r.knowledgePointOrEmpty().isBlank(),
                "判分应给出 knowledge_point（T8 错题本按它聚合，缺了会退化成用题目前 30 字兜底）");
    }
}
