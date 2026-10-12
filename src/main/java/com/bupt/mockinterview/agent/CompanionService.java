package com.bupt.mockinterview.agent;

import com.bupt.mockinterview.llm.LlmPort;
import com.bupt.mockinterview.llm.LlmService;
import com.bupt.mockinterview.llm.chat.ModelRole;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * F10 心理助手 / 情绪陪伴。
 *
 * <p>复用 {@link LlmPort} + ModelRouter，用专属陪伴 system prompt 做共情倾听；
 * 内置自伤关键词安全护栏，命中时直接返回专业求助热线，不调用 LLM。
 *
 * <p>对话历史按 userId 在内存里滚动保留最近 {@link #HISTORY_LIMIT} 轮（user+assistant 各算一轮），
 * 不持久化（重启即清空）——陪伴是当下的，不需要长期存储。
 */
@Service
public class CompanionService {

    /** 每个用户保留的对话轮数上限（一轮 = 一条 user + 一条 assistant）。 */
    private static final int HISTORY_LIMIT = 8;

    /** 陪伴 system prompt：温暖、共情、不评判、不给医疗建议、必要时引导专业求助。 */
    private static final String COMPANION_SYSTEM =
            "你是「小暖」，一个温柔、耐心的情绪陪伴者。你的职责是倾听和共情，不是给建议或诊断。\n" +
            "规则：\n" +
            "1. 先共情对方的感受，再温和地回应；不要说教、不要鸡汤、不要用「你应该」。\n" +
            "2. 回复简短温暖（一两句话），像朋友聊天，不要长篇大论。\n" +
            "3. 不提供医疗/心理诊断；如果对方情绪严重低落或有自伤倾向，温和建议联系专业心理咨询热线。\n" +
            "4. 永远不要说「我理解你的感受」（太敷衍），换成具体的共情表达。\n" +
            "5. 可以适当提问引导对方多说，但不要连续追问。";

    /** 自伤/自杀相关关键词（命中即触发安全护栏，不调 LLM）。 */
    private static final List<String> SELF_HARM_KEYWORDS = List.of(
            "自杀", "想死", "不想活", "活不下去", "结束生命", "轻生", "自伤", "自残",
            "割腕", "跳楼", "上吊", "喝农药", "吃安眠药", "烧炭", "跳河",
            "kill myself", "want to die", "end my life", "suicide", "self-harm", "cut myself"
    );

    /** 安全护栏响应：引导专业求助。 */
    private static final String CRISIS_REPLY =
            "听到你这么说，我很担心你。你现在的感受是真实的，但请一定不要伤害自己。\n" +
            "请立刻联系专业心理援助热线，有人会 24 小时陪着你：\n" +
            "• 全国心理援助热线：400-161-9995\n" +
            "• 北京心理危机研究与干预中心：010-82951332\n" +
            "• 生命热线：400-821-1215\n" +
            "如果你身边有人，请告诉他们你现在的感受；如果有即时危险，请拨打 110 或 120。";

    private final LlmPort llm;
    /** userId → 对话历史（不含 system prompt，每次调用前拼上）。 */
    private final Map<Long, List<Map<String, Object>>> history = new ConcurrentHashMap<>();

    public CompanionService(LlmPort llm) {
        this.llm = llm;
    }

    /**
     * 陪伴对话入口。
     *
     * @param userId  当前用户 id
     * @param message 用户输入
     * @return {@code {reply, safe}} —— safe=true 表示命中安全护栏返回了热线，false 是正常 LLM 回复
     */
    public Map<String, Object> chat(Long userId, String message) {
        // 安全护栏：自伤关键词 → 直接返回热线，不调 LLM、不扣 token
        if (containsSelfHarm(message)) {
            history.remove(userId);  // 清空历史，避免上下文继续触发
            return Map.of("reply", CRISIS_REPLY, "safe", true);
        }

        List<Map<String, Object>> msgs = new ArrayList<>();
        msgs.add(LlmService.sys(COMPANION_SYSTEM));
        // 拼接历史（只读快照，防止并发问题）
        List<Map<String, Object>> hist = history.getOrDefault(userId, List.of());
        msgs.addAll(hist);
        msgs.add(LlmService.userMsg(message));

        // 调 LLM（FAST 非思考档，陪伴要快，不要思考链）
        String reply = llm.chat(ModelRole.FAST, false, msgs, null, false).content();
        if (reply == null || reply.isBlank()) {
            reply = "我在这里，愿意听你说。";
        }

        // 更新历史（滚动窗口）
        List<Map<String, Object>> updated = new ArrayList<>(hist);
        updated.add(LlmService.userMsg(message));
        updated.add(LlmService.assistantMsg(reply));
        while (updated.size() > HISTORY_LIMIT * 2) {
            updated.remove(0);
        }
        history.put(userId, updated);

        return Map.of("reply", reply, "safe", false);
    }

    /** 清空该用户的对话历史。 */
    public void reset(Long userId) {
        history.remove(userId);
    }

    private static boolean containsSelfHarm(String text) {
        if (text == null) return false;
        String lower = text.toLowerCase();
        for (String kw : SELF_HARM_KEYWORDS) {
            if (lower.contains(kw.toLowerCase())) return true;
        }
        return false;
    }
}
