package com.bupt.mockinterview.security;

import com.bupt.mockinterview.domain.KeyMode;
import com.bupt.mockinterview.domain.User;
import com.bupt.mockinterview.domain.UserApiKey;
import com.bupt.mockinterview.domain.repository.UserApiKeyRepository;
import com.bupt.mockinterview.llm.LlmProperties;
import com.bupt.mockinterview.llm.chat.QwenChatProvider;
import com.bupt.mockinterview.rag.DashscopeProperties;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * DeepSeek 调用 key 路由（R1-3，，替代旧 KeyVault 的取 key 职责）。
 * <ul>
 *   <li>无用户上下文（userId=null，测试 profile / 系统内部调用）→ 平台 env key；</li>
 *   <li>DeepSeek + PLATFORM 用户 → 平台 env key（{@code DEEPSEEK_API_KEY}）；</li>
 *   <li>DeepSeek + BYOK 用户 → 解密该用户 {@link UserApiKey} 中的自带 key；</li>
 *   <li><b>Qwen（任何用户，含 BYOK）→ 平台 {@code DASHSCOPE_API_KEY}，模式 PLATFORM</b>
 *       （用户自带 key 体系只支持 DeepSeek；Qwen API 费由平台 token 额度承担，R3-2 决策）。</li>
 * </ul>
 * embedding（DashScope）不走本路由——始终平台 key。
 */
@Component
public class KeyResolver {

    /** 本次调用实际使用的 key 与模式；调用方按 mode 决定是否扣 BYOK 服务费。 */
    public record ResolvedKey(String apiKey, KeyMode mode) {
        public boolean byok() {
            return mode == KeyMode.BYOK;
        }
    }

    private final LlmProperties props;
    private final DashscopeProperties dashProps;
    private final UserApiKeyRepository apiKeyRepo;
    private final UserKeyCipher cipher;

    public KeyResolver(LlmProperties props, DashscopeProperties dashProps,
                       UserApiKeyRepository apiKeyRepo, UserKeyCipher cipher) {
        this.props = props;
        this.dashProps = dashProps;
        this.apiKeyRepo = apiKeyRepo;
        this.cipher = cipher;
    }

    /**
     * 解析当前用户对话调用应使用的 key（等价于 {@link #resolveForChat(User, String)}
     * 传 DeepSeek 厂商；R3-1 前的旧调用点保留）。
     *
     * @param user 当前请求用户；null（未鉴权的内部/测试路径）时走平台 key
     */
    @Transactional(readOnly = true)
    public ResolvedKey resolveForChat(User user) {
        return resolveForChat(user, "deepseek");
    }

    /**
     * 按对话厂商标识解析本次调用应使用的 key。
     *
     * <p>R3-1：当前唯一对话厂商 DeepSeek，{@code providerId} 不影响结果。
     * R3-2 起：{@code "qwen"} 恒走平台 {@code DASHSCOPE_API_KEY}（BYOK 用户也如此），
     * DeepSeek 维持 PLATFORM env / BYOK 解密两路。
     *
     * @param user       当前请求用户；null（未鉴权的内部/测试路径）时走平台 key
     * @param providerId 厂商标识（{@code "deepseek"} / {@code "qwen"}）
     */
    @Transactional(readOnly = true)
    public ResolvedKey resolveForChat(User user, String providerId) {
        // Qwen 恒走平台 DashScope key（BYOK 用户也一样），按 PLATFORM 实扣 token
        if (QwenChatProvider.ID.equals(providerId)) {
            return new ResolvedKey(dashProps.getApiKey(), KeyMode.PLATFORM);
        }
        if (user == null || user.getKeyMode() == KeyMode.PLATFORM) {
            return new ResolvedKey(props.getApiKey(), KeyMode.PLATFORM);
        }
        UserApiKey row = apiKeyRepo.findByUserId(user.getId())
                .orElseThrow(() -> new IllegalStateException("你的账号处于 BYOK 模式但未保存 API Key，请先在「模型调用方式」中填写"));
        return new ResolvedKey(cipher.decrypt(row.getKeyCiphertext()), KeyMode.BYOK);
    }
}
