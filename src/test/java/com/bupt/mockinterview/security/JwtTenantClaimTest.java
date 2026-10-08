package com.bupt.mockinterview.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R2-T0：JWT {@code tid} claim 的纯单元测试。
 * 新令牌带 tid=userId；R1 时代的旧令牌无 tid，解析为 null，由 JwtAuthFilter 回退 sub。
 */
class JwtTenantClaimTest {

    private static final String SECRET = "unit-test-secret-unit-test-secret-32b"; // 40 字节 ≥32

    private final JwtService jwtService = new JwtService(props(SECRET));

    private static JwtProperties props(String secret) {
        JwtProperties p = new JwtProperties();
        p.setSecret(secret);
        return p;
    }

    @Test
    void newToken_carriesTid_equalToUserId() {
        Claims claims = jwtService.parseToken(jwtService.generateToken(42L, "alice"));
        assertThat(JwtService.userIdFrom(claims)).isEqualTo(42L);
        assertThat(JwtService.tenantIdFrom(claims)).as("新令牌必须带 tid").isEqualTo(42L);
    }

    @Test
    void legacyToken_withoutTid_resolvesNullForCallerFallback() throws Exception {
        // 用同密钥手工签一枚 R1 形态旧令牌（只有 sub + username，没有 tid）
        String legacy = Jwts.builder()
                .subject("77")
                .claim("username", "old-user")
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)))
                .compact();

        Claims claims = jwtService.parseToken(legacy);
        assertThat(JwtService.userIdFrom(claims)).isEqualTo(77L);
        assertThat(JwtService.tenantIdFrom(claims))
                .as("旧令牌无 tid → null，由过滤器回退取 sub")
                .isNull();
    }
}
