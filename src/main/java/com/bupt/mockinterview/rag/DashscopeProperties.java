package com.bupt.mockinterview.rag;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 通义（Dashscope）embedding 配置，绑定 `dashscope.*`。
 * api-key 始终从环境变量 {@code DASHSCOPE_API_KEY} 读，默认空串（启动不报错，用到才校验）。
 */
@Component
@ConfigurationProperties(prefix = "dashscope")
@Getter
@Setter
public class DashscopeProperties {

    private String baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1";

    /** 空默认：未 set 时向量化会降级（T6 编排层 catch 后跳过检索，不中断面试）。 */
    private String apiKey = "";

    private String embedModel = "qwen3.7-text-embedding-flash";

    /**
     * R3-2 通义千问对话模型（compatible-mode 与 embedding 共用 base-url / key）。
     * 模型名迭代快，以百炼控制台实测为准；判分/Critic 用的 reasoner 必须经实测
     * 支持「enable_thinking=true + response_format=json_object」组合。
     */
    private String chatModelFast = "qwen3.8-flash";
    private String chatModelReasoner = "qwen3.7-plus";

    public boolean enabled() {
        return apiKey != null && !apiKey.isBlank();
    }
}
