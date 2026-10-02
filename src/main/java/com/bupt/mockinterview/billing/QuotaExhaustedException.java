package com.bupt.mockinterview.billing;

/**
 * 服务点数耗尽（R1-3）：BYOK 模式下余额不足以支付本次调用服务费。
 * 由 ApiExceptionHandler 映射为 402 QUOTA_EXHAUSTED；抛出点在真正发起 LLM HTTP 调用之前。
 */
public class QuotaExhaustedException extends RuntimeException {

    public QuotaExhaustedException(String message) {
        super(message);
    }
}
