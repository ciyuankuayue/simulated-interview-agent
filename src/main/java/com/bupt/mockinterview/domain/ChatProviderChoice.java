package com.bupt.mockinterview.domain;

/**
 * 用户级对话模型厂商选择（R3-2）。与 {@link KeyMode} 解耦：
 * 任何调用方式下都可选通义千问；BYOK 用户选 QWEN 时仍走平台 DashScope key（按平台 token 扣）。
 */
public enum ChatProviderChoice {
    DEEPSEEK,
    QWEN
}
