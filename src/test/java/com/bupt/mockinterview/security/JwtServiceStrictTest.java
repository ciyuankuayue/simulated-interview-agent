package com.bupt.mockinterview.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * R2-T0 批 2（D4/C2）：{@link JwtService} 在 strict（prod 口径）下的密钥 fail-fast。
 * 纯构造级 L1 测试，不起 Web：缺密钥 / 密钥过短必须直接抛异常终止启动，
 * 不允许零填充或临时密钥（非 strict 的 dev 便利路径必须保留）。
 */
class JwtServiceStrictTest {

    private static JwtProperties props(String secret) {
        JwtProperties p = new JwtProperties();
        p.setSecret(secret);
        return p;
    }

    @Test
    void strict_missingSecret_failsStartup() {
        assertThatThrownBy(() -> new JwtService(props(null), new TenantProperties(true)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT_SECRET");
        assertThatThrownBy(() -> new JwtService(props("   "), new TenantProperties(true)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT_SECRET");
    }

    @Test
    void strict_secretShorterThan32Bytes_failsStartup() {
        String shortSecret = "a".repeat(31); // 31 字节 < HMAC-SHA256 要求的 32
        assertThatThrownBy(() -> new JwtService(props(shortSecret), new TenantProperties(true)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32");
    }

    @Test
    void strict_secretExactly32Bytes_startsNormally() {
        String secret32 = "a".repeat(32);
        assertThatCode(() -> new JwtService(props(secret32), new TenantProperties(true)))
                .doesNotThrowAnyException();
    }

    @Test
    void nonStrict_missingOrShortSecret_keepsDevConvenience() {
        // dev/test：缺密钥走临时随机密钥；短密钥零填充——旧行为不回归
        assertThatCode(() -> new JwtService(props(null), new TenantProperties(false)))
                .doesNotThrowAnyException();
        assertThatCode(() -> new JwtService(props("short"), new TenantProperties(false)))
                .doesNotThrowAnyException();
        // 单参构造（默认非 strict）继续可用
        assertThatCode(() -> new JwtService(props("a".repeat(32)))).doesNotThrowAnyException();
    }
}
