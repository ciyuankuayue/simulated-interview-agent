package com.bupt.mockinterview.llm.chat;

import com.bupt.mockinterview.llm.LlmException;

/** 流已失败，但已收到的输出/usage 必须交给统一门面结算，不能重放给用户。 */
public class ChatStreamException extends LlmException {
    private final ChatResponse partialResponse;

    public ChatStreamException(String message, Throwable cause, ChatResponse partialResponse) {
        super(message, cause);
        this.partialResponse = partialResponse;
    }

    /** 尚未收到任何模型输出或 usage 时为 null。 */
    public ChatResponse partialResponse() {
        return partialResponse;
    }
}
