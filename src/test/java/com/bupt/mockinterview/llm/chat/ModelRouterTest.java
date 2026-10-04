package com.bupt.mockinterview.llm.chat;

import com.bupt.mockinterview.domain.ChatProviderChoice;
import com.bupt.mockinterview.domain.User;
import com.bupt.mockinterview.llm.LlmException;
import com.bupt.mockinterview.llm.LlmProperties;
import com.bupt.mockinterview.rag.DashscopeProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * R3-1 路由单测（批 1：唯一厂商 DeepSeek）：
 * 角色 → 配置模型名映射正确；任何用户（含 null）都走 deepseek；流水 label 带厂商前缀；
 * provider 未注册时给明确异常（Spring 装配故障快速暴露）。
 */
class ModelRouterTest {

    private ModelRouter routerWith(ChatProvider provider, LlmProperties props) {
        return new ModelRouter(provider == null ? List.of() : List.of(provider), props);
    }

    @Test
    void fastRole_mapsToModelFlash_andReasonerToModelReasoner() {
        LlmProperties props = new LlmProperties();
        props.setModelFlash("flash-x");
        props.setModelReasoner("reasoner-y");
        ChatProvider deepSeek = mock(ChatProvider.class);
        when(deepSeek.id()).thenReturn(DeepSeekChatProvider.ID);
        ModelRouter router = routerWith(deepSeek, props);

        ModelRouter.Route fast = router.route(null, ModelRole.FAST);
        ModelRouter.Route reasoner = router.route(mock(User.class), ModelRole.REASONER);

        assertThat(fast.provider()).isSameAs(deepSeek);
        assertThat(fast.model()).isEqualTo("flash-x");
        assertThat(fast.providerId()).isEqualTo("deepseek");
        assertThat(fast.label()).isEqualTo("deepseek:flash-x");

        assertThat(reasoner.model()).isEqualTo("reasoner-y");
        assertThat(reasoner.providerId()).isEqualTo("deepseek");
        assertThat(reasoner.label()).isEqualTo("deepseek:reasoner-y");
    }

    @Test
    void missingDeepSeekProvider_throwsLlmException() {
        ModelRouter router = routerWith(null, new LlmProperties());
        assertThatThrownBy(() -> router.route(null, ModelRole.FAST))
                .isInstanceOf(LlmException.class)
                .hasMessageContaining("deepseek");
    }

    @Test
    void qwenUser_routesToQwenProvider_andDashscopeModelNames() {
        LlmProperties llmProps = new LlmProperties();
        DashscopeProperties dashProps = new DashscopeProperties();
        dashProps.setChatModelFast("qwen-fast-z");
        dashProps.setChatModelReasoner("qwen-reasoner-z");
        ChatProperties chatProps = new ChatProperties();
        ChatProvider deepSeek = mock(ChatProvider.class);
        when(deepSeek.id()).thenReturn(DeepSeekChatProvider.ID);
        ChatProvider qwen = mock(ChatProvider.class);
        when(qwen.id()).thenReturn(QwenChatProvider.ID);
        ModelRouter router = new ModelRouter(List.of(deepSeek, qwen), llmProps, dashProps, chatProps);

        User user = new User("r3alice", "hash");
        user.setChatProvider(ChatProviderChoice.QWEN);

        ModelRouter.Route fast = router.route(user, ModelRole.FAST);
        ModelRouter.Route reasoner = router.route(user, ModelRole.REASONER);

        assertThat(fast.provider()).isSameAs(qwen);
        assertThat(fast.providerId()).isEqualTo("qwen");
        assertThat(fast.model()).isEqualTo("qwen-fast-z");
        assertThat(fast.label()).isEqualTo("qwen:qwen-fast-z");
        assertThat(reasoner.model()).isEqualTo("qwen-reasoner-z");
        assertThat(reasoner.label()).isEqualTo("qwen:qwen-reasoner-z");
    }

    @Test
    void nullUser_usesConfiguredDefaultProvider() {
        DashscopeProperties dashProps = new DashscopeProperties();
        ChatProperties chatProps = new ChatProperties();
        chatProps.setDefaultProvider(QwenChatProvider.ID);
        ChatProvider qwen = mock(ChatProvider.class);
        when(qwen.id()).thenReturn(QwenChatProvider.ID);
        ModelRouter router = new ModelRouter(List.of(qwen), new LlmProperties(), dashProps, chatProps);

        ModelRouter.Route route = router.route(null, ModelRole.FAST);

        assertThat(route.providerId()).isEqualTo("qwen");
        assertThat(route.model()).isEqualTo(dashProps.getChatModelFast());
    }

    @Test
    void qwenChosenButProviderMissing_throwsLlmException() {
        User user = new User("r3bob", "hash");
        user.setChatProvider(ChatProviderChoice.QWEN);
        ChatProvider deepSeek = mock(ChatProvider.class);
        when(deepSeek.id()).thenReturn(DeepSeekChatProvider.ID);
        ModelRouter router = new ModelRouter(List.of(deepSeek), new LlmProperties(),
                new DashscopeProperties(), new ChatProperties());

        assertThatThrownBy(() -> router.route(user, ModelRole.FAST))
                .isInstanceOf(LlmException.class)
                .hasMessageContaining("qwen");
    }
}
