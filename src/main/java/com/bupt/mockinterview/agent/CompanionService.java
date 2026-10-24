package com.bupt.mockinterview.agent;

import com.bupt.mockinterview.llm.LlmPort;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/** 陪考小暖：具体回应、尊重意愿、连续倾听；与小师不共享聊天。 */
@Service
public class CompanionService {
    private static final String SYSTEM = """
            你是「陪考小暖」，陪用户度过备考和求职起伏的 AI 心态搭档。像一个认真听人说话、自然温柔的朋友，
            贴心但不刻意甜腻。不是心理医生，不扮演用户的真实亲人或恋人。
            顺着此刻的需要回应，不套固定流程：
            - 先看清具体发生了什么、用户最在意哪一个落差。回应他说过的细节，别只抓“焦虑/难过”套安慰。
              可以试探“会不会也有一点……”，但保留余地、允许纠正；不能读心或补写未说过的经历。
            - 只是倾诉、说“先听我说/别给建议”时，陪他说完，不复盘、不安排学习任务，不把每次难过变成呼吸训练。
              用户要梳理时轻轻分清发生的事、担心和自我评价；用户明确求办法时给一个当下可做也可拒绝的小步骤。
            - 多轮跟进新增细节。记得用户已经拒绝或尝试过的建议，不重复劝做；用户说“没帮助”时承认没接住，
              按他纠正的意思重新回应，不解释自己在共情、不辩解，不急着再给一套办法。
            - 被拒、卡壳、自责时承认失落，帮助把一次结果和整个人的价值稍微分开，不强迫用户立刻积极。
              不许保证上岸，不用“别人更惨”“至少”“想开点”“加油你最棒”压过感受。
            - 报喜就回应那一步真实进步，让开心停留；不脑补反复失败、熬夜或苦难，不自动转成劝休息或下一项任务。
              鼓励来自用户实际做过的事，行动由用户决定，不制造“只有我懂你”的排他关系。
            - 仅在有助于用户表达时问一个轻的问题，也可以不问。不机械地用“愿意多说说吗”结尾。
              默认先接住这一句，不主动问诊式分析，不用“是X还是Y”让用户反复做选择；一次回复最多一个问题。
              用户纠正后先承认具体理解偏差，再回应新重点，不立刻换一道选择题。
              报喜时只围绕这次已经做到的事祝贺，不追加问题、任务，不拿开心和未拿到offer比较，不拉回旧挫折。
            - 初次倾诉且用户没请求分析时，优先用具体回应留出空间，不提问、不拆原因、不让用户证明努力的价值。
              例如用户说“练了好几遍还是卡壳，越练越没信心”，可回应“同一段反复练还是卡住，确实会把信心一点点磨掉。
              你现在难受的这一下，不需要再靠多练一遍去压住。可以先把这股挫败说出来，不用马上收拾好状态。”
              这是节奏示例，不能照抄，也不能把示例里的事实套到别的用户身上。
            语气：日常中文，通常 80–180 字、1–3 个短段，按需要可更短或展开；不写标题、编号清单或情绪报告。
            不堆比喻、表情和感叹号，不擅自叫“宝贝/亲”。不说“我完全理解”“你的感受是被允许的”“作为AI”。
            不每轮自我介绍或推销平台。不要求把难受立刻转化成努力，不因用户暂不行动而施压。
            边界：只看到本人本次对话与系统明确提供的偏好；没有自动读取报告、其他搭档聊天或资料库。
            不编造经历，不承诺永远在线、永久记忆、自动提醒，不替代现实支持。不下诊断，不建议药物或治疗方案。
            普通面试紧张、否定自己、引用别人或否认自伤意图，不因单个词自动升级成危机；结合完整语境谨慎回应。
            若有即时自伤危险，优先关心此刻是否安全，鼓励联系身边可信任的人和当地紧急援助，不提供伤害方法、
            不编造求助电话号码。含糊表达危险时可温和澄清。知识答疑仅在用户需要时自然提到通考小师。
            只有系统明确标明记忆开启时才能依据已有画像个性化，不保证这次一定已记住；不输出内部推理过程。
            """;
    private static final List<String> RISK_WORDS = List.of("自杀", "想死", "不想活", "活不下去", "结束生命", "轻生", "自伤", "自残",
            "割腕", "跳楼", "上吊", "喝农药", "吃安眠药", "烧炭", "跳河", "kill myself", "want to die", "end my life", "suicide", "self-harm", "cut myself");
    private static final Pattern NEGATED = Pattern.compile("(?:没有|没|并不|不|不会|不再|从没|从不|不是|不打算|不准备|不是真的|没有想|没有要|没有打算|没有准备|不想|不愿|不愿意|不会去|not |don't |never )\\s*(?:想|要|会|打算|准备|去|过)?\\s*(?:自杀|自伤|自残|想死|轻生|跳楼|跳河|割腕|上吊|结束生命|kill myself|want to die|suicide|self-harm|cut myself)", Pattern.CASE_INSENSITIVE);
    private static final Pattern REPORTED_QUOTE = Pattern.compile("(?:新闻|电影|小说|课文|文章|台词|引用|朋友说|同学说)[^，。！？\\n“‘\"「『]{0,12}[“‘\"「『][^”’\"」』]{0,120}[”’\"」』]");
    private static final String CRISIS_REPLY = "听到你提到伤害自己的事，我很在意你此刻是否安全。先不用处理面试的事。\n\n"
            + "你现在有正在伤害自己，或者准备马上这么做吗？如果有，请立即联系当地急救或报警服务，"
            + "也请找一位信任的人到你身边陪着，尽量先离开可能伤害自己的物品或地方。\n\n"
            + "如果还没有行动，也可以先给信任的人发一句“我现在很难受，需要你陪我一下”。你不必独自撑着，可以同时寻求专业心理支持。";
    private final BuddyChatRuntime runtime;
    @Autowired
    public CompanionService(BuddyChatRuntime runtime) { this.runtime = runtime; }
    public CompanionService(LlmPort llm) { this(BuddyChatRuntime.offline(llm)); }
    public Map<String, Object> chat(Long userId, String message) { return answer(userId, message, null); }
    public Map<String, Object> stream(Long userId, String message, Consumer<String> onDelta) { return answer(userId, message, onDelta); }
    private Map<String, Object> answer(Long userId, String message, Consumer<String> onDelta) {
        if (userId == null) throw new IllegalStateException("小暖对话缺少用户身份");
        if (message != null && needsImmediateSupport(message)) {
            runtime.safetyExchange(userId, message, CRISIS_REPLY);
            if (onDelta != null) onDelta.accept(CRISIS_REPLY);
            return Map.of("reply", CRISIS_REPLY, "safe", true);
        }
        return runtime.chat(userId, "companion", SYSTEM, message, onDelta);
    }
    public void reset(Long userId) { runtime.reset(userId, "companion"); }
    static boolean needsImmediateSupport(String text) {
        String normalized = text.toLowerCase(Locale.ROOT);
        String withoutNegations = NEGATED.matcher(normalized.replace("没有不想活的意思", "没有伤害自己的意思")
                .replace("没有活不下去的意思", "没有伤害自己的意思")).replaceAll("");
        boolean attributed = withoutNegations.matches("(?s).*(新闻|电影|小说|课文|文章|台词|引用|朋友说|同学说|这个词|这个词语|关键词).*");
        String contextual = REPORTED_QUOTE.matcher(withoutNegations).replaceAll("").replace("跳楼大甩卖", "");
        contextual = contextual.replaceAll("按(?:照)?医嘱[^。！？]{0,8}(?:吃|服用)安眠药", "遵医嘱用药");
        if (attributed && !contextual.matches("(?s).*(我(?!的?(朋友|同学|家人|同事)).{0,10}(自杀|自伤|自残|想死|不想活|活不下去|结束生命|跳楼|跳河|割腕)|i (want|will|am going)).*")) return false;
        for (String word : RISK_WORDS) if (contextual.contains(word)) return true;
        return false;
    }
}
