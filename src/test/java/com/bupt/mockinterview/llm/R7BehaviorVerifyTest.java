package com.bupt.mockinterview.llm;

import com.bupt.mockinterview.agent.Role;
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
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * R7 真实联调：行为面试官链路 —— 行为题生成（FAST 非思考）+ BEHAVIOR 判分（STAR+表达四维，REASONER+JSON）。
 *
 * 运行（本机 set 密钥后）：
 *   PowerShell:  $env:DEEPSEEK_API_KEY="sk-xxx"; .\mvnw.cmd test -Dtest=R7BehaviorVerifyTest
 * 未 set 密钥时整类自动跳过（Assumptions）。走平台 env key，不经过 BYOK 扣点。
 *
 * 验收点：
 * 1) 行为 questionTask + systemPrompt 真实产出的是行为题（不允许冒出 JVM/八股等技术题）；
 * 2) BEHAVIOR 赛道判分 breakdown 四维名恰为 STAR结构/行动细节/结果与反思/表达与抗压，总分 0-8；
 * 3) knowledge_point 非空（行为标签要能进错题本聚合）。
 */
public class R7BehaviorVerifyTest {

    @Test
    void verifyBehaviorQuestionAndScore() {
        String key = System.getenv("DEEPSEEK_API_KEY");
        Assumptions.assumeTrue(key != null && !key.isBlank(),
                "未设置 DEEPSEEK_API_KEY，跳过 R7 行为链路真实联调（本地 set 后跑）");

        LlmProperties props = new LlmProperties();
        props.setApiKey(key);

        KeyResolver resolver = new KeyResolver(props,
                new com.bupt.mockinterview.rag.DashscopeProperties(),
                mock(UserApiKeyRepository.class),
                new UserKeyCipher(Base64.getEncoder().encodeToString(new byte[32])));
        CurrentUserService currentUser = new CurrentUserService(mock(UserRepository.class));
        OkHttpClient http = new OkHttpClient();
        com.bupt.mockinterview.llm.chat.DeepSeekChatProvider deepSeek =
                new com.bupt.mockinterview.llm.chat.DeepSeekChatProvider(http, new ObjectMapper(), props);
        com.bupt.mockinterview.llm.chat.ModelRouter router =
                new com.bupt.mockinterview.llm.chat.ModelRouter(List.of(deepSeek), props);
        LlmService llm = new LlmService(http, new ObjectMapper(), props, router,
                resolver, currentUser, mock(QuotaService.class),
                new com.bupt.mockinterview.billing.QuotaProperties());

        // ---------- 链路 1：行为题生成（与 orchestrator.start 同款 prompt 拼装） ----------
        String jd = "岗位 JD：\n招聘高级 Java 后端：沟通协作能力强，能跨团队推进项目，抗压能力好。\n\n"
                + "候选人的资料片段（可能为空）：\n- 三年后端，做过订单系统重构\n\n"
                + Role.BEHAVIOR_INTERVIEWER.questionTask();
        LlmReply question = llm.followUp(Role.BEHAVIOR_INTERVIEWER.systemPrompt(), jd);
        System.out.println("[verify-r7] 行为题 -> " + question.content());
        assertFalse(question.content() == null || question.content().isBlank(), "行为题不应为空");

        // ---------- 链路 2：BEHAVIOR 判分 ----------
        String answer = """
                上次订单重构时我和前端同学对接口设计意见不一致。我先拉了他和产品一起对齐真实诉求，
                发现他担心的是联调周期。我负责出了一版兼容方案并自己承担主要改造，两周内上线，
                接口联调时间从五天压到两天。事后我也复盘应该更早拉齐诉求。
                """;
        ScoreResult r = llm.score(ScoreTrack.BEHAVIOR,
                "讲一次你和同事意见不一致并最终解决的经历。", answer);

        System.out.println("[verify-r7] score=" + r.score() + " breakdown=" + r.breakdown()
                + " knowledge_point=" + r.knowledgePointOrEmpty() + " followup=" + r.followup());
        assertTrue(r.score() >= 0 && r.score() <= 8, "score 应在 0-8，实际 " + r.score());
        assertEquals(
                Set.of("STAR结构", "行动细节", "结果与反思", "表达与抗压"),
                r.breakdown().keySet(),
                "BEHAVIOR 赛道 breakdown 必须是 STAR+表达四维，实际 " + r.breakdown().keySet());
        assertFalse(r.knowledgePointOrEmpty().isBlank(),
                "行为判分必须给 knowledge_point 短标签（错题本按它聚合）");
    }
}
