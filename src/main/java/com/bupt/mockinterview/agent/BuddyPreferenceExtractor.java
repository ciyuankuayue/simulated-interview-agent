package com.bupt.mockinterview.agent;

import com.bupt.mockinterview.memory.BuddyMemoryService.Candidate;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Conservative backup for explicit first-person preferences a model may omit from its footer. */
final class BuddyPreferenceExtractor {
    private static final String START = "(?:^|[，。！？;；\\n])\\s*";
    private static final Pattern ROLE = Pattern.compile(START + "(?:我的目标(?:岗位|方向)?|目标岗位)(?:是|为|：|:)?([^，。！？;；\\n]{2,60})");
    private static final Pattern EXPLANATION = Pattern.compile(START + "我(?:平时|通常|一直)?(?:更)?(?:喜欢|习惯|希望)([^，。！？;；\\n]{2,80})");
    private static final Pattern SUPPORT = Pattern.compile(START + "(?:以后|平时|通常|下次)(?:我[^，。！？;；\\n]{0,18}时)?(?:请|希望你|你)?([^。！？;；\\n]{2,100})");

    private BuddyPreferenceExtractor() {}

    static List<Candidate> explicit(String message, String mode) {
        List<Candidate> candidates = new ArrayList<>();
        if (message.matches("(?s).*(别记|不要记|不许记|不保存|忘掉|忘记).*")) return candidates;
        collect(candidates, ROLE, message, "target_role", null);
        if (mode.equals("coach")) collect(candidates, EXPLANATION, message, "explanation_style", "先|讲|例|原理|代码|比喻|图|步骤|简短|详细|直接");
        if (mode.equals("companion")) collect(candidates, SUPPORT, message, "support_style", "倾诉|听我|建议|安慰|语气|陪|温柔|耐心");
        return candidates;
    }

    private static void collect(List<Candidate> result, Pattern pattern, String message, String key, String required) {
        Matcher match = pattern.matcher(message);
        if (!match.find()) return;
        String value = match.group(1).trim();
        if (required != null && !value.matches("(?s).*(" + required + ").*")) return;
        result.add(new Candidate(key, value, match.group().trim()));
    }
}
