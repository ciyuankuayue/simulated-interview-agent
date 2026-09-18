package com.bupt.mockinterview.llm;

/** LLM 调用失败（含重试耗尽、HTTP 4xx、响应/JSON 校验失败）。降级策略由上层（T6 agent）决定。 */
public class LlmException extends RuntimeException {

    public LlmException(String message) {
        super(message);
    }

    public LlmException(String message, Throwable cause) {
        super(message, cause);
    }
}
