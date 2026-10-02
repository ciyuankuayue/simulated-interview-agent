package com.bupt.mockinterview.llm;

/**
 * 候选 DeepSeek key 验活结果（R1-3，PUT /api/user/api-key 用）。
 */
public enum ApiKeyStatus {
    /** 200：key 有效。 */
    VALID,
    /** 401/403：key 无效或被拒（前端提示重填，HTTP 映射 400 INVALID_API_KEY）。 */
    INVALID,
    /** 网络异常 / 5xx：服务商暂不可达（HTTP 映射 502 PROVIDER_UNREACHABLE，key 不落库）。 */
    UNREACHABLE
}
