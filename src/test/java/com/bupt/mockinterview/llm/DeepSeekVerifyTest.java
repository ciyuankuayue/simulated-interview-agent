package com.bupt.mockinterview.llm;

import com.bupt.mockinterview.billing.QuotaService;
import com.bupt.mockinterview.domain.repository.UserApiKeyRepository;
import com.bupt.mockinterview.domain.repository.UserRepository;
import com.bupt.mockinterview.security.CurrentUserService;
import com.bupt.mockinterview.security.KeyResolver;
import com.bupt.mockinterview.security.UserKeyCipher;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * T5 真实联调：验证 DeepSeek 两条链路 ——「追问生成（非思考）」与「判分 JSON（思考 + 结构化输出）」。
 *
 * 运行（需本机 set 密钥，沙盒无限流不跑）：
 *   PowerShell:  $env:DEEPSEEK_API_KEY="sk-xxx"; .\mvnw.cmd test -Dtest=DeepSeekVerifyTest
 * 未 set 密钥时整类自动跳过（Assumptions）。
 *
 * R1-3：无 UserContext（测试直接构造）→ KeyResolver 走平台 env key，不经过 BYOK 扣点。
 */
public class DeepSeekVerifyTest {

    @Test
    void verifyFollowUpAndScore() throws Exception {
        String key = System.getenv("DEEPSEEK_API_KEY");
        Assumptions.assumeTrue(key != null && !key.isBlank(),
                "未设置 DEEPSEEK_API_KEY，跳过真实 LLM 联调（本地 set 后跑）");

        LlmProperties props = new LlmProperties();
        props.setApiKey(key); // baseUrl / model 用默认值（deepseek-flash）

        KeyResolver resolver = new KeyResolver(props,
                new com.bupt.mockinterview.rag.DashscopeProperties(),
                mock(UserApiKeyRepository.class),
                new UserKeyCipher(Base64.getEncoder().encodeToString(new byte[32])));
        CurrentUserService currentUser = new CurrentUserService(mock(UserRepository.class));
        OkHttpClient http = new OkHttpClient();
        com.bupt.mockinterview.llm.chat.DeepSeekChatProvider deepSeek =
                new com.bupt.mockinterview.llm.chat.DeepSeekChatProvider(http, new ObjectMapper(), props);
        com.bupt.mockinterview.llm.chat.ModelRouter router =
                new com.bupt.mockinterview.llm.chat.ModelRouter(java.util.List.of(deepSeek), props);
        LlmService llm = new LlmService(http, new ObjectMapper(), props, router,
                resolver, currentUser, mock(QuotaService.class),
                new com.bupt.mockinterview.billing.QuotaProperties());

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
        ScoreResult r = llm.score(ScoreTrack.TECH, question, answer);

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
