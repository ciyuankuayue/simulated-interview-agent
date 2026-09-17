package com.bupt.mockinterview.rag.experiment;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 把「Java 后端 AI 面试知识框架.md」解析成语料：每个叶级知识点一行 → 一个 Doc。
 * - Doc.text 前缀最近一级标题（如「多线程并发：synchronized、Lock、AQS」），利于 embedding 区分。
 * - Doc.id 用 {@link #slug(String)} 稳定化，供 L3 实验的 ground truth 对齐到具体知识点。
 *
 * 解析规则：标题行（#/##/###/####）只更新上下文，不入库；遇到第一个「## 」后才开始收叶节点；
 * 标题/引言/目录阶段的非空行跳过。
 */
public final class CorpusLoader {

    /** 非(字母/数字/中文) 的字符统一成 '-'，用于稳定 slug。Java 的 \w 仅 ASCII，故显式纳入 \u4e00-\u9fa5。 */
    private static final Pattern NON_WORD = Pattern.compile("[^\\w\\u4e00-\\u9fa5]+");

    private CorpusLoader() {
    }

    public static List<Doc> load(String classpathResource) throws IOException {
        List<Doc> docs = new ArrayList<>();
        String h2 = "", h3 = "", h4 = "";
        boolean started = false; // 遇到第一个 ## 才开始收叶节点
        try (InputStream in = CorpusLoader.class.getClassLoader().getResourceAsStream(classpathResource);
             BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            if (in == null) {
                throw new IOException("语料资源未找到: " + classpathResource);
            }
            String line;
            while ((line = r.readLine()) != null) {
                String t = line.trim();
                if (t.isEmpty()) {
                    continue;
                }
                if (t.startsWith("#### ")) {
                    h4 = t.substring(5).trim();
                } else if (t.startsWith("### ")) {
                    h3 = t.substring(4).trim();
                    h4 = "";
                } else if (t.startsWith("## ")) {
                    h2 = t.substring(3).trim();
                    h3 = "";
                    h4 = "";
                    started = true;
                } else if (t.startsWith("#")) {
                    // 文档标题 / 目录标题，跳过
                } else {
                    if (!started) {
                        continue; // 引言 / 目录阶段
                    }
                    String prefix = !h4.isEmpty() ? h4 : (!h3.isEmpty() ? h3 : h2);
                    String text = prefix.isEmpty() ? t : (prefix + "：" + t);
                    docs.add(new Doc(slug(t), text));
                }
            }
        }
        return docs;
    }

    /** 稳定 slug：非(字母/数字/中文) 统一成 '-'，去首尾 '-'。L3 ground truth 用同一函数对齐 Doc.id。 */
    public static String slug(String s) {
        String r = NON_WORD.matcher(s.trim()).replaceAll("-");
        while (r.startsWith("-")) {
            r = r.substring(1);
        }
        while (r.endsWith("-")) {
            r = r.substring(0, r.length() - 1);
        }
        return r;
    }
}
