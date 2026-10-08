package com.bupt.mockinterview.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.Map;

/**
 * JWT 令牌签发与验证（R1-1）。
 * <p>
 * 使用 HMAC-SHA256 签名（jjwt 0.12.x）。密钥来源优先级：
 * <ol>
 *   <li>{@code jwt.secret} 显式配置（生产必须）</li>
 *   <li>启动时自动生成 32 字节随机密钥（仅开发便利，重启即失效）</li>
 * </ol>
 */
@Service
public class JwtService {

    private static final Logger log = LoggerFactory.getLogger(JwtService.class);

    private final SecretKey key;
    private final long expiryMs;

    public JwtService(JwtProperties props) {
        String secret = props.getSecret();
        if (secret == null || secret.isBlank()) {
            // 开发便利：自动生成 32 字节密钥，Base64URL 编码
            byte[] bytes = new byte[32];
            new SecureRandom().nextBytes(bytes);
            secret = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            log.warn("jwt.secret 未配置，启动时自动生成临时密钥（重启后所有 token 失效）。"
                    + "生产必须在 application.yml 或 JWT_SECRET 环境变量显式设至少 32 字节密钥。");
        }
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < 32) {
            // HMAC-SHA256 要求至少 32 字节
            byte[] padded = new byte[32];
            System.arraycopy(keyBytes, 0, padded, 0, Math.min(keyBytes.length, 32));
            keyBytes = padded;
            log.warn("jwt.secret 不足 32 字节，已用零补齐（生产必须显式设足够长的密钥）。");
        }
        this.key = Keys.hmacShaKeyFor(keyBytes);
        this.expiryMs = props.getExpiryMs();
    }

    /**
     * 签发 JWT。
     *
     * @param userId   用户 ID（放 {@code sub} claim）；同时写入 {@code tid} 租户 claim（R2-T0：MVP 同值）
     * @param username 用户名（额外 claim）
     * @return JWT 字符串
     */
    public String generateToken(Long userId, String username) {
        Instant now = Instant.now();
        Instant exp = now.plusMillis(expiryMs);

        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("username", username)
                .claim("tid", userId)
                .issuedAt(Date.from(now))
                .expiration(Date.from(exp))
                .signWith(key)
                .compact();
    }

    /**
     * 验证 JWT 并提取 claims。
     *
     * @param token JWT 字符串
     * @return claims（包含 sub=userId, username, exp）
     * @throws io.jsonwebtoken.JwtException 令牌过期 / 签名无效 / 格式错误
     */
    public Claims parseToken(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    /**
     * 从 claims 提取 userId。
     */
    public static long userIdFrom(Claims claims) {
        return Long.parseLong(claims.getSubject());
    }

    /**
     * 从 claims 提取 username。
     */
    public static String usernameFrom(Claims claims) {
        return claims.get("username", String.class);
    }

    /**
     * 从 claims 提取租户 ID（R2-T0）。新令牌含 {@code tid}；旧令牌无此 claim 时返回 null，
     * 由调用方回退取 {@code sub}（userId）派生，保证旧令牌向后兼容。
     */
    public static Long tenantIdFrom(Claims claims) {
        Number tid = claims.get("tid", Number.class);
        return tid == null ? null : tid.longValue();
    }
}
