package com.bupt.mockinterview.security;

import com.bupt.mockinterview.domain.KeyMode;
import com.bupt.mockinterview.domain.User;
import com.bupt.mockinterview.domain.UserApiKey;
import com.bupt.mockinterview.domain.repository.UserApiKeyRepository;
import com.bupt.mockinterview.llm.LlmProperties;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * DeepSeek 调用 key 路由（R1-3，2026-10-02，替代旧 KeyVault 的取 key 职责）。
 * <ul>
 *   <li>无用户上下文（userId=null，测试 profile / 系统内部调用）→ 平台 env key；</li>
 *   <li>PLATFORM 用户 → 平台 env key（{@code DEEPSEEK_API_KEY}）；</li>
 *   <li>BYOK 用户 → 解密该用户 {@link UserApiKey} 中的自带 key。</li>
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
    private final UserApiKeyRepository apiKeyRepo;
    private final UserKeyCipher cipher;

    public KeyResolver(LlmProperties props, UserApiKeyRepository apiKeyRepo, UserKeyCipher cipher) {
        this.props = props;
        this.apiKeyRepo = apiKeyRepo;
        this.cipher = cipher;
    }

    /**
     * 解析当前用户对话调用应使用的 DeepSeek key。
     *
     * @param user 当前请求用户；null（未鉴权的内部/测试路径）时走平台 key
     */
    @Transactional(readOnly = true)
    public ResolvedKey resolveForChat(User user) {
        if (user == null || user.getKeyMode() == KeyMode.PLATFORM) {
            return new ResolvedKey(props.getApiKey(), KeyMode.PLATFORM);
        }
        UserApiKey row = apiKeyRepo.findByUserId(user.getId())
                .orElseThrow(() -> new IllegalStateException("你的账号处于 BYOK 模式但未保存 API Key，请先在「模型调用方式」中填写"));
        return new ResolvedKey(cipher.decrypt(row.getKeyCiphertext()), KeyMode.BYOK);
    }
}
