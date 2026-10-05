package com.bupt.mockinterview.domain;

/**
 * 用户对话模型（DeepSeek）调用方式（R1-3）。
 * <ul>
 *   <li>{@link #PLATFORM}：平台持有 key（env 配置），R1-3 不扣用户点，R2 起按 token 计费。</li>
 *   <li>{@link #BYOK}：Bring Your Own Key，走用户自己加密存储的 DeepSeek key，
 *       每次调用扣 1 点服务费，余额不足 402。</li>
 * </ul>
 * embedding（DashScope）永远走平台 key，与本模式无关。
 */
public enum KeyMode {
    PLATFORM,
    BYOK
}
