package com.bupt.mockinterview.llm.chat;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 对话路由全局配置（R3-2），绑定 {@code app.llm.*}。
 *
 * @param defaultProvider 无用户上下文（测试 profile / 系统内部调用）时使用的对话厂商；
 *                        默认 {@code deepseek}，生产多用户场景一律按用户选择路由
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.llm")
public class ChatProperties {

    private String defaultProvider = DeepSeekChatProvider.ID;
}
