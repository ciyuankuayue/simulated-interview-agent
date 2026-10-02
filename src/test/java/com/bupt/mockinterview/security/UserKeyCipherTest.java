package com.bupt.mockinterview.security;

import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * R1-3 用户 BYOK key 加密器单测：AES-256-GCM 往返、IV 随机性、KEK 不匹配/密文篡改必失败。
 * 不走 Spring——直接构造并手动调包级 {@code init()}（容器里由 @PostConstruct 触发）。
 */
class UserKeyCipherTest {

    private static String fixedKek() {
        return Base64.getEncoder().encodeToString(new byte[32]); // 测试用全零 KEK
    }

    @Test
    void encryptThenDecrypt_roundtripsPlaintext() {
        UserKeyCipher cipher = new UserKeyCipher(fixedKek());
        cipher.init();

        String key = "sk-1234567890abcdefABCDEFGHIJKLMN";
        String stored = cipher.encrypt(key);

        assertThat(stored).isNotEqualTo(key);
        assertThat(cipher.decrypt(stored)).isEqualTo(key);
    }

    @Test
    void encryptTwice_producesDifferentCiphertexts_dueToRandomIv() {
        UserKeyCipher cipher = new UserKeyCipher(fixedKek());
        cipher.init();

        String key = "sk-same-input-key-0000000000";
        assertThat(cipher.encrypt(key)).isNotEqualTo(cipher.encrypt(key));
    }

    @Test
    void decryptWithDifferentKek_fails() {
        UserKeyCipher a = new UserKeyCipher(fixedKek());
        a.init();
        byte[] otherRaw = new byte[32];
        otherRaw[0] = 1;
        UserKeyCipher b = new UserKeyCipher(Base64.getEncoder().encodeToString(otherRaw));
        b.init();

        String stored = a.encrypt("sk-secret-aaaaaaaaaaaaaaaaaaaa");
        // GCM tag 校验失败 → 模拟生产换了 APP_DATA_KEY 的场景
        assertThatThrownBy(() -> b.decrypt(stored))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("解密失败");
    }

    @Test
    void tamperedCiphertext_fails() {
        UserKeyCipher cipher = new UserKeyCipher(fixedKek());
        cipher.init();

        String stored = cipher.encrypt("sk-tamper-test-000000000000");
        char last = stored.charAt(stored.length() - 1);
        String flipped = stored.substring(0, stored.length() - 1) + (last == 'A' ? 'B' : 'A');

        assertThatThrownBy(() -> cipher.decrypt(flipped))
                .isInstanceOf(IllegalStateException.class);
    }
}
