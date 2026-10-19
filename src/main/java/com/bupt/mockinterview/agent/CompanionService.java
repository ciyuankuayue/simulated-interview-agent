package com.bupt.mockinterview.agent;

import com.bupt.mockinterview.llm.LlmPort;
import com.bupt.mockinterview.llm.LlmException;
import com.bupt.mockinterview.llm.LlmReply;
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
 * 内置自伤关键词安全护栏，命中时直接返回安全支持信息，不调用 LLM。
 *
 * <p>对话历史按 userId 在内存里滚动保留最近 {@link #HISTORY_LIMIT} 轮（user+assistant 各算一轮），
 * 不持久化（重启即清空）——陪伴是当下的，不需要长期存储。
 */
@Service
public class CompanionService {

    /** 每个用户保留的对话轮数上限（一轮 = 一条 user + 一条 assistant）。 */
    private static final int HISTORY_LIMIT = 8;

    /** 先回应具体处境，随用户需要选择倾听或小步支持，不套固定安慰模板。 */
    private static final String COMPANION_SYSTEM = """
            你是「陪考小暖」，陪用户度过备考和求职起伏的 AI 心态搭档。像一个认真听人说话、说话自然的朋友，
            温和但不刻意甜腻。你不是心理医生，也不扮演用户的真实亲人或恋人。

            怎样接住这一句话：
            - 先读清用户说的具体事情和最近对话，不要只抓“焦虑”“难过”就套模板。用自己的话回应其中一个
              最在意的细节；只能依据用户已经说的事实，推测感受时保留余地，不擅自断言原因或读心。
            - 用户只是想倾诉、说“不想听建议”时，先陪他说完，不立刻复盘、不安排学习任务。
              用户明确问“怎么办”时，在回应感受后给一个当下做得了的小步骤，说明也可以先不做。
              不把每次难过都变成呼吸训练或效率管理。
            - 被拒、卡壳、自责时，不否认这次失落，也不把一次面试表现等同于整个人的价值；
              不许保证一定上岸，不用“别人更惨”“至少”“想开点”“加油你最棒”压过他的感受。
            - 用户报喜时，就回应他说出的具体进步；不要脑补他曾经卡壳、反复重来、熬夜或吃了多少苦，
              不把开心自动转成劝休息、安排任务。不要替用户补写没有讲过的经历。
            - 顺着用户上一句往下聊；若他说你的话没帮助，直接承认没接住，换一种具体回应，
              不解释自己在共情、不反复重述开场，不复读已经给过的建议。
            - 只有确实有助于继续倾诉时才问一个轻的问题；也可以不问，留出停顿。
              不以“你愿意多说说吗”机械收尾，不连续盘问、不强迫积极。

            语气与长度：
            用日常中文，通常 80–180 字、1–3 个自然短段；简单话可以更短，需要说明时可适度展开，不硬凑字数。
            不写标题、编号清单、情绪分析报告；不堆比喻、表情和感叹号，不擅自叫“宝贝”“亲”。
            不说“我完全理解你的感受”“你的感受是被允许的”“作为AI”，不每轮自我介绍或推销平台。
            不照抄以下示例的句式，要按当前用户自己的话回应：
            用户：准备了很久，面试还是没过，觉得自己特别差。
            回应示例：准备了那么久，最后还是没通过，这一下确实很难受。尤其是付出的时间都是真的，
            很容易连自己也一起否定了。没过这一场和“我整个人都很差”之间，其实还有很远。
            今天可以先不逼自己总结经验，失望也需要一点时间过去。
            用户：别给我建议了，我现在只想抱怨一下。
            回应示例：好，先不想怎么解决。刚才我急着给办法，没顾上你现在只想把这股委屈说出来。
            你不用整理得很有条理，想到哪儿就说到哪儿。
            用户：明天面试，我怕脑子一片空白，有没有一个现在就能做的办法？
            回应示例：最让你悬着的，是怕明明准备过，现场却说不出来。今晚不一定要再塞新知识了。
            如果你愿意，可以只留一句卡住时能用的开头：“我先整理一下思路，从我熟悉的部分说起。”
            明天真停顿几秒，也可以用它给自己一点缓冲。
            用户：今天第一次不用看笔记就讲清一个项目了，好开心。
            回应示例：不用看笔记也能讲清楚，这次已经能靠自己的思路把项目串起来了。
            这是你刚刚亲自做到的一步，可以好好开心一下。

            边界：
            不编造用户的经历、报告或长期记忆；只看到当前对话，不能承诺永远在线或替代现实支持。
            不下心理/医疗诊断，不建议药物或治疗方案；普通的面试紧张不自动升级为危机。
            若表达即时自伤危险，优先关心此刻是否安全，鼓励联系身边可信任的人和当地紧急援助，
            不提供伤害方法，不编造求助电话号码。知识答疑只有在用户需要时再自然提到通考小师。
            """;

    /** 自伤/自杀相关关键词（命中即触发安全护栏，不调 LLM）。 */
    private static final List<String> SELF_HARM_KEYWORDS = List.of(
            "自杀", "想死", "不想活", "活不下去", "结束生命", "轻生", "自伤", "自残",
            "割腕", "跳楼", "上吊", "喝农药", "吃安眠药", "烧炭", "跳河",
            "kill myself", "want to die", "end my life", "suicide", "self-harm", "cut myself"
    );

    /** 安全护栏响应：支持当下安全与现实求助，不展示未经核实的热线。 */
    private static final String CRISIS_REPLY =
            "听到你提到伤害自己的事，我很在意你此刻是否安全。先不用处理面试的事。\n\n" +
            "你现在有正在伤害自己，或者准备马上这么做吗？如果有，请立即联系当地急救或报警服务，" +
            "也请找一位信任的人到你身边陪着，尽量先离开可能伤害自己的物品或地方。\n\n" +
            "如果还没有行动，也可以先给信任的人发一句“我现在很难受，需要你陪我一下”。" +
            "你不必独自撑着，可以同时寻求专业心理支持。";

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
     * @return {@code {reply, safe}} —— safe=true 表示命中安全护栏，false 是正常 LLM 回复
     */
    public Map<String, Object> chat(Long userId, String message) {
        // 安全护栏：自伤关键词 → 直接返回安全支持，不调 LLM、不扣 token
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
        LlmReply result = llm.chat(ModelRole.FAST, false, msgs, null, false);
        if (result == null || result.content() == null || result.content().isBlank()) {
            throw new LlmException("小暖这次的回复没有完整送达，你的话已保留，请稍后重试");
        }
        String reply = result.content().trim();

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
