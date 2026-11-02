package com.bupt.mockinterview.billing;

import com.bupt.mockinterview.domain.KeyMode;

/**
 * token 额度耗尽（R2）：预检/扣费时余额不足。
 * 由 ApiExceptionHandler 映射为 402 QUOTA_EXHAUSTED；预检抛出点在真正发起外部调用之前。
 */
public class QuotaExhaustedException extends RuntimeException {

    public static final String BETA_SUPPORT = "平台目前处于内测阶段，请联系工作人员补充额度。微信：13481264947";
    public static final String PLATFORM_MESSAGE = "积分已用完。" + BETA_SUPPORT;
    public static final String BYOK_MESSAGE = "自带 API 的服务积分已用完。" + BETA_SUPPORT;

    public QuotaExhaustedException(String message) {
        super(message);
    }

    /** 按调用模式提示积分不足；内测阶段由工作人员人工补额，无在线支付。 */
    public static QuotaExhaustedException forMode(KeyMode mode) {
        return new QuotaExhaustedException(mode == KeyMode.BYOK ? BYOK_MESSAGE : PLATFORM_MESSAGE);
    }
}
