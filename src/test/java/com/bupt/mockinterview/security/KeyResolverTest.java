package com.bupt.mockinterview.security;

import com.bupt.mockinterview.domain.KeyMode;
import com.bupt.mockinterview.domain.User;
import com.bupt.mockinterview.domain.UserApiKey;
import com.bupt.mockinterview.domain.repository.UserApiKeyRepository;
import com.bupt.mockinterview.llm.LlmProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Base64;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * R1-3 KeyResolver 路由单测（不走 Spring/网络）：
 * null 用户 / PLATFORM → 平台 env key；BYOK 有 key → 解密用户 key；BYOK 无 key → IllegalStateException（→409）。
 */
class KeyResolverTest {

    private LlmProperties props;
    private UserApiKeyRepository repo;
    private UserKeyCipher cipher;
    private KeyResolver resolver;

    @BeforeEach
    void setUp() {
        props = new LlmProperties();
        props.setApiKey("sk-platform-env");
        repo = mock(UserApiKeyRepository.class);
        cipher = new UserKeyCipher(Base64.getEncoder().encodeToString(new byte[32]));
        cipher.init();
        resolver = new KeyResolver(props, repo, cipher);
    }

    private User user(KeyMode mode) {
        User u = new User("alice", "hash");
        ReflectionTestUtils.setField(u, "id", 42L);
        u.setKeyMode(mode);
        return u;
    }

    @Test
    void nullUser_routesToPlatformKey() {
        KeyResolver.ResolvedKey rk = resolver.resolveForChat(null);
        assertThat(rk.apiKey()).isEqualTo("sk-platform-env");
        assertThat(rk.byok()).isFalse();
    }

    @Test
    void platformUser_routesToPlatformKey_andNeverTouchesUserKeyTable() {
        KeyResolver.ResolvedKey rk = resolver.resolveForChat(user(KeyMode.PLATFORM));
        assertThat(rk.apiKey()).isEqualTo("sk-platform-env");
        assertThat(rk.mode()).isEqualTo(KeyMode.PLATFORM);
    }

    @Test
    void byokUser_decryptsOwnKey() {
        String plain = "sk-byok-alice-private-key-00000";
        UserApiKey row = new UserApiKey(user(KeyMode.BYOK), cipher.encrypt(plain), "0000");
        when(repo.findByUserId(42L)).thenReturn(Optional.of(row));

        KeyResolver.ResolvedKey rk = resolver.resolveForChat(user(KeyMode.BYOK));
        assertThat(rk.byok()).isTrue();
        assertThat(rk.apiKey()).isEqualTo(plain);
    }

    @Test
    void byokUserWithoutStoredKey_throwsIllegalState_mapsTo409() {
        when(repo.findByUserId(42L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> resolver.resolveForChat(user(KeyMode.BYOK)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("BYOK");
    }
}
