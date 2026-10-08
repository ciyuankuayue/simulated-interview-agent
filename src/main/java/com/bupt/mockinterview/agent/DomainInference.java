package com.bupt.mockinterview.agent;

import java.util.List;
import java.util.Optional;

/**
 * 领域识别（R2 C4 修复 S2）：从 JD / 简历文本轻量推断目标岗位方向。
 *
 * <p>规则版（零 LLM 成本、可单测）。完整 LLM 推断并把 targetDomain 持久化到
 * JdProfile 见 r2-iteration-plan.md 的 S2 / R2 脊柱（exam_track + flow_template + question_bank）。
 *
 * <p>本类只回答「这段文字大概率指向哪个岗位方向」，不负责出题；出题侧据此注入「领域约束」，
 * 取代原先写死在 Role systemPrompt 里的「Java 后端面试官」人格。
 */
public final class DomainInference {

    private DomainInference() {}

    private record Rule(String domain, List<String> keywords) {}

    // 顺序即优先级；越靠前越具体（前端优先于后端，避免 "java 后端" 误吞前端 JD）。
    // 命中即返回，不再继续。关键词均为小写，infer 入参统一转小写后 contains 匹配。
    private static final List<Rule> RULES = List.of(
            new Rule("前端", List.of("前端", "frontend", "front-end", "vue", "react", "angular",
                    "javascript", "typescript", "css", "html5")),
            new Rule("测试 / QA", List.of("测试", "qa", "自动化测试", "selenium", "pytest", "功能测试")),
            new Rule("数据分析 / 数据开发", List.of("数据分析", "数据开发", "数仓", "etl", "bi 分析")),
            new Rule("算法 / 机器学习", List.of("算法", "algorithm", "机器学习", "machine learning",
                    "深度学习", "nlp", "cv", "推荐", "大模型")),
            new Rule("运维 / DevOps", List.of("运维", "devops", "sre", "k8s", "kubernetes", "docker", "ci/cd")),
            new Rule("产品经理", List.of("产品经理", "product manager", "需求分析")),
            new Rule("Java 后端", List.of("java 后端", "java后端", "后端", "backend", "spring",
                    "springboot", "spring boot", "jvm", "微服务")),
            new Rule("考公", List.of("公务员", "考公", "行测", "申论")),
            new Rule("考研", List.of("考研", "研究生", "初试", "复试", "调剂"))
    );

    /**
     * @param jd     岗位 JD 原文（可为 null）
     * @param resume 简历 / 资料片段（可为 null）
     * @return 命中的岗位方向；都没命中返回 empty（调用方据此走「不假设固定领域」防御）
     */
    public static Optional<String> infer(String jd, String resume) {
        String text = ((jd == null ? "" : jd) + " " + (resume == null ? "" : resume))
                .toLowerCase();
        for (Rule r : RULES) {
            for (String kw : r.keywords()) {
                if (text.contains(kw)) {
                    return Optional.of(r.domain());
                }
            }
        }
        return Optional.empty();
    }
}
