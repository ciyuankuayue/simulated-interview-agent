package com.bupt.mockinterview.billing;

import com.bupt.mockinterview.domain.KeyMode;

/**
 * token 额度耗尽（R2）：预检/扣费时余额不足。
 * 由 ApiExceptionHandler 映射为 402 QUOTA_EXHAUSTED；预检抛出点在真正发起外部调用之前。
 */
public class QuotaExhaustedException extends RuntimeException {

    public static final String PLATFORM_MESSAGE = "Token 额度已用完，请联系管理员充值";
    public static final String BYOK_MESSAGE = "服务点数已用完：删除你的 Key 切回平台模式，或联系管理员充值";

    public QuotaExhaustedException(String message) {
        super(message);
    }

    /** 按当前调用模式给不同引导文案（PLATFORM=找管理员；BYOK=可删 key 切回平台）。 */
    public static QuotaExhaustedException forMode(KeyMode mode) {
        return new QuotaExhaustedException(mode == KeyMode.BYOK ? BYOK_MESSAGE : PLATFORM_MESSAGE);
    }
}
