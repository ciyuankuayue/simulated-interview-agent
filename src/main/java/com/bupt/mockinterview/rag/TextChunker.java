package com.bupt.mockinterview.rag;

import java.util.ArrayList;
import java.util.List;

/**
 * 文本切块：先按空行分段落，长段再硬切；相邻块重叠 {@code overlap} 字符保留跨段上下文。
 * md 与纯文本通用（MVP 输入形态，决策见 domain.md）。
 */
public final class TextChunker {

    private TextChunker() {}

    public static List<String> chunk(String text, int maxChars, int overlap) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<String> paras = new ArrayList<>();
        for (String raw : text.split("\\n\\s*\\n")) {
            String p = raw.strip();
            if (p.isEmpty()) {
                continue;
            }
            if (p.length() <= maxChars) {
                paras.add(p);
            } else {
                int step = Math.max(1, maxChars - overlap);
                for (int i = 0; i < p.length(); i += step) {
                    int end = Math.min(p.length(), i + maxChars);
                    paras.add(p.substring(i, end));
                }
            }
        }
        return paras;
    }
}
