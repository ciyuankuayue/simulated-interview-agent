package com.bupt.mockinterview.billing;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Conservative public list-price estimates: peak, no cache/discount. Not vendor invoices. */
public final class ModelCosts {
    public static final String VERSION = "2026-10-30-list-v1";
    private ModelCosts() {}
    public static Long estimateMicros(String label, long prompt, long completion, long total) {
        BigDecimal[] rates = rates(label, prompt);
        if (rates == null) return null;
        if (prompt + completion < total) {
            // Missing split: price unknown tokens at the higher output rate.
            completion = Math.max(0, total - Math.max(0, prompt));
        }
        return rates[0].multiply(BigDecimal.valueOf(Math.max(0, prompt)))
                .add(rates[1].multiply(BigDecimal.valueOf(Math.max(0, completion))))
                .setScale(0, RoundingMode.CEILING).longValueExact();
    }
    public static long reserveMicros(String label, long inputTokenBound, int outputBound, int attempts) {
        Long estimate = estimateMicros(label, inputTokenBound, outputBound, inputTokenBound + outputBound);
        if (estimate == null) throw new PlatformBudgetExceededException("该模型尚未配置平台成本价格，请联系管理员");
        return Math.multiplyExact(estimate, attempts);
    }
    private static BigDecimal[] rates(String label, long prompt) {
        if (label == null) return null;
        if (label.equals("deepseek:deepseek-flash")) return pair("2", "8");
        if (label.equals("qwen:qwen3.8-flash")) return pair("0.8", "2.7");
        if (label.equals("qwen:qwen3.7-plus")) return prompt <= 256000 ? pair("2", "8") : pair("6", "24");
        if (label.equals("dashscope:qwen3.7-text-embedding-flash")) return pair("0.125", "0.125");
        return null;
    }
    private static BigDecimal[] pair(String a, String b) { return new BigDecimal[]{new BigDecimal(a), new BigDecimal(b)}; }
}
