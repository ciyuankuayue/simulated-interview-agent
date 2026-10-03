package com.bupt.mockinterview.billing;

/**
 * 厂商响应缺失 usage 时的 token 估算兜底（R2）。
 * <p>
 * 启发式：按字符数 1:1 估算。中文内容里 1 个汉字约 1 个 token，此口径对中文偏保守（略多扣，
 * 防平台漏算）；英文会偏多，但仅在厂商异常不回 usage 时兜底，正常路径永远用真实 usage。
 */
public final class UsageEstimates {

    private UsageEstimates() {
    }

    /** 多段文本（如全部输入消息 + 输出）的总字符估算 token 数，至少 1。 */
    public static long estimateChars(CharSequence... texts) {
        long chars = 0L;
        if (texts != null) {
            for (CharSequence t : texts) {
                if (t != null) {
                    chars += t.length();
                }
            }
        }
        return Math.max(1L, chars);
    }
}
