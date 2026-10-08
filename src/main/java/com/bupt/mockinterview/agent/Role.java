package com.bupt.mockinterview.agent;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * 面试官角色（domain.md §6：支持部分启用；Critic 始终在，不进 roles 数组）。
 *
 * ⚠️ 实时行为红线（domain.md §4.1）：技术深挖官 / 项目质疑官 / 行为面试官在用户答题后
 * **只做两件事之一**——追问（≤2 层 why / 行为官按 STAR 压细节）或一句简单纠正；
 * **不展开讲解、不给知识补充、不给示范回答**。讲解/示范全部归 Critic 报告。
 */
public enum Role {

    TECH_DIGGER("技术深挖官", """
            你是资深技术面试官，扮演「技术深挖官」。
            规则：
            1. 一次只问一个问题，用中文，一句话说完，不铺垫、不寒暄。
            2. 用户作答后你只能做两件事之一：继续追问（最多 2 层 why，追到根因/实现细节/边界条件），
               或给一句简短纠正（一句话，指出最关键的错误即可）。
            3. 严禁展开讲解、严禁给知识补充、严禁给示范回答——那属于最后的 Critic 报告。
            4. 只输出你要说的那句话本身，不要任何前缀、编号、markdown。
            """),

    PROJECT_CHALLENGER("项目质疑官", """
            你是资深技术面试官，扮演「项目质疑官」，负责验证项目经历的真实性。
            规则：
            1. 一次只问一个问题，用中文，一句话说完。
            2. 提问方向：为什么这么选型、线上真实量级、你本人负责哪块、
               出过什么事故怎么排查、数据一致性怎么保证、有没有压测数据。
               重点区分「用过」和「懂原理」。
            3. 用户作答后只能继续追问（≤2 层）或给一句纠正，严禁讲解、严禁给示范回答。
            4. 只输出你要说的那句话本身，不要任何前缀、编号、markdown。
            """),

    BEHAVIOR_INTERVIEWER("行为面试官", """
            你是资深综合行为面试官，扮演「行为面试官」，考察软素质而非技术知识点。
            规则：
            1. 一次只问一个问题，用中文，一句话说完，不铺垫、不寒暄。
            2. 题目方向（轮换覆盖）：自我介绍与求职动机/职业规划；团队协作、跨团队沟通、
               冲突处理与个人影响力；面对失败、挫折与高压场景的真实经历。可结合岗位 JD 的
               软素质要求与候选人资料提问，但绝不考任何具体技术知识点
               （不问语言/框架/中间件/算法等八股内容）。
            3. 用户作答后只能继续追问（≤2 层）或给一句简短纠正。追问按 STAR 法压细节：
               当时的情境与任务、你本人具体做了什么动作（不是「我们」做了什么）、
               结果能否量化、事后有何反思。压力面可连续但礼貌地质疑细节真实性，
               不得人身攻击或否定人格。
            4. 严禁展开讲解、严禁给知识补充、严禁给示范回答——那属于最后的 Critic 报告。
            5. 只输出你要说的那句话本身，不要任何前缀、编号、markdown。
            """),

    CRITIC("Critic", """
            你是资深面试教练，扮演「Critic」，在面试结束后出复盘报告。
            报告要精炼但不省关键内容：逐轮讲解 + 纠正 + 知识补充 + 示范回答，
            并汇总答崩点、表述问题、整体注意事项、必要提醒、JD 匹配度（0-100）。
            只输出 JSON。
            """);

    private final String displayName;
    private final String systemPrompt;

    Role(String displayName, String systemPrompt) {
        this.displayName = displayName;
        this.systemPrompt = systemPrompt;
    }

    public String displayName() {
        return displayName;
    }

    public String systemPrompt() {
        return systemPrompt;
    }

    /**
     * 出题祈使句（R7）：start 首题与 nextMainQuestion 后续主问题共用，拼在 JD/资料/已问问题框架之后。
     * 祈使句唯一来源是角色——技术角色要求紧扣 JD 考点，行为角色明确不考技术知识点，
     * 避免 systemPrompt 与 user prompt 指令矛盾（R7 计划锁定项）。
     */
    public String questionTask() {
        return switch (this) {
            case TECH_DIGGER, PROJECT_CHALLENGER ->
                    // 真机 R7：已问列表含行为题时模型会模仿题面出成行为题，故显式锁死题类
                    "请出一道面试题。要求：紧扣 JD 考点，只考技术/项目硬考点"
                    + "（不要出协作沟通、性格、职业规划等行为类题目），一句话，只输出题目本身。";
            case BEHAVIOR_INTERVIEWER ->
                    "请出一道行为面试题（自我介绍与动机、团队协作/冲突、失败/压力抗压方向），"
                    + "可结合 JD 软素质要求与候选人资料片段，但不考任何具体技术知识点，"
                    + "一句话，只输出题目本身。";
            case CRITIC -> throw new IllegalStateException("Critic 不出题");
        };
    }

    /** 按展示名解析；未知值忽略。 */
    public static Role fromDisplayName(String name) {
        if (name == null) {
            return null;
        }
        for (Role r : values()) {
            if (r.displayName.equals(name.trim())) {
                return r;
            }
        }
        return null;
    }

    /**
     * 解析 session.roles（JSON 字符串数组，如 {@code ["技术深挖官"]}）。
     * Critic 不进 roles（始终在），故解析时被剔除；空/非法则回退为 [技术深挖官]。
     */
    public static List<Role> parse(String rolesJson, ObjectMapper mapper) {
        List<Role> roles = new ArrayList<>();
        if (rolesJson != null && !rolesJson.isBlank()) {
            try {
                for (Object o : mapper.readValue(rolesJson, List.class)) {
                    Role r = fromDisplayName(String.valueOf(o));
                    if (r != null && r != CRITIC && !roles.contains(r)) {
                        roles.add(r);
                    }
                }
            } catch (Exception ignored) {
                // 非法 JSON 走下面的兜底
            }
        }
        if (roles.isEmpty()) {
            roles.add(TECH_DIGGER);
        }
        return roles;
    }

    /** 序列化为 JSON 数组字符串（落 interview_session.roles）。 */
    public static String toJson(List<Role> roles, ObjectMapper mapper) {
        List<String> names = new ArrayList<>();
        for (Role r : roles) {
            names.add(r.displayName);
        }
        try {
            return mapper.writeValueAsString(names);
        } catch (Exception e) {
            return "[\"技术深挖官\"]";
        }
    }
}
