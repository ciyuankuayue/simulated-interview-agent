package com.bupt.mockinterview.llm.chat;

import com.bupt.mockinterview.domain.ChatProviderChoice;
import com.bupt.mockinterview.domain.User;
import com.bupt.mockinterview.llm.LlmException;
import com.bupt.mockinterview.llm.LlmProperties;
import com.bupt.mockinterview.rag.DashscopeProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 模型路由（R3-1 引入，R3-2 扩通义千问）：把「用户 + 逻辑角色」解析为「厂商 provider + 具体模型名」。
 *
 * <p>厂商选择规则：
 * <ul>
 *   <li>登录用户：按 {@code user.chatProvider}（DEEPSEEK 默认 / QWEN）；</li>
 *   <li>无用户上下文（测试 profile / 系统内部调用）：按 {@code app.llm.default-provider}（默认 deepseek）。</li>
 * </ul>
 * 角色→模型名：DeepSeek 取 {@link LlmProperties}，Qwen 取 {@link DashscopeProperties} 的 chat-* 配置。
 */
@Component
public class ModelRouter {

    /** 一次解析结果。 */
    public record Route(ChatProvider provider, String model) {
        public String providerId() {
            return provider.id();
        }

        /** 流水模型标识：{@code 厂商标识:具体模型名}，如 deepseek:deepseek-flash / qwen:qwen3.8-flash。 */
        public String label() {
            return provider.id() + ":" + model;
        }
    }

    private final Map<String, ChatProvider> providers;
    private final LlmProperties props;
    private final DashscopeProperties dashProps;
    private final ChatProperties chatProps;

    /** Spring 装配用全参构造。 */
    @Autowired
    public ModelRouter(List<ChatProvider> providerList, LlmProperties props,
                       DashscopeProperties dashProps, ChatProperties chatProps) {
        this.providers = providerList.stream()
                .collect(Collectors.toMap(ChatProvider::id, Function.identity()));
        this.props = props;
        this.dashProps = dashProps;
        this.chatProps = chatProps;
    }

    /** 离线测试便捷构造：DashScope 与 app.llm 均用默认值（默认厂商 deepseek）。 */
    public ModelRouter(List<ChatProvider> providerList, LlmProperties props) {
        this(providerList, props, new DashscopeProperties(), new ChatProperties());
    }

    /**
     * @param user 当前用户；null（测试 profile / 无鉴权内部调用）时走全局默认厂商
     * @param role FAST / REASONER
     */
    public Route route(User user, ModelRole role) {
        String providerId;
        if (user == null) {
            providerId = chatProps.getDefaultProvider();
        } else {
            providerId = user.getChatProvider() == ChatProviderChoice.QWEN
                    ? QwenChatProvider.ID : DeepSeekChatProvider.ID;
        }
        ChatProvider provider = providers.get(providerId);
        if (provider == null) {
            throw new LlmException("对话模型厂商未注册: " + providerId);
        }
        String model = modelName(providerId, role);
        return new Route(provider, model);
    }

    private String modelName(String providerId, ModelRole role) {
        if (QwenChatProvider.ID.equals(providerId)) {
            return role == ModelRole.REASONER ? dashProps.getChatModelReasoner() : dashProps.getChatModelFast();
        }
        return role == ModelRole.REASONER ? props.getModelReasoner() : props.getModelFlash();
    }
}
