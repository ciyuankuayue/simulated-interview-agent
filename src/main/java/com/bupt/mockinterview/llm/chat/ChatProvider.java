package com.bupt.mockinterview.llm.chat;

/**
 * 对话厂商 SPI（R3-1）：每家大模型一个实现（DeepSeek / 通义千问…）。
 * 实现负责该厂商的 HTTP 协议、请求体差异、重试与错误归一化；
 * 计费（预检/实扣/流水）统一在 LlmService，不在实现内做。
 */
public interface ChatProvider {

    /** 厂商标识（流水/路由 key），如 {@code "deepseek"} / {@code "qwen"}。 */
    String id();

    /**
     * 发起一次对话调用。
     *
     * @param req    厂商无关请求（model 已由 router 解析为该厂商的具体模型名）
     * @param apiKey 本次调用使用的 key（PLATFORM env 或 BYOK 解密值，由上层路由）
     * @return 厂商无关响应；失败（含重试耗尽/4xx/空 key）抛 {@link com.bupt.mockinterview.llm.LlmException}
     */
    ChatResponse chat(ChatRequest req, String apiKey);
}
