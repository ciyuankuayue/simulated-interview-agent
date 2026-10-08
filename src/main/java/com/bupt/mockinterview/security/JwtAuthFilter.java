package com.bupt.mockinterview.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * JWT 鉴权过滤器（R1-1）。
 * <p>
 * 默认启用。可通过配置 {@code app.auth.jwt-enabled=false} 禁用（测试 profile 用）。
 * 其余行为见类注释历史（auth 白名单、SSE query fallback 等）。
 */
@Component
@ConditionalOnProperty(name = "app.auth.jwt-enabled", havingValue = "true", matchIfMissing = true)
public class JwtAuthFilter extends OncePerRequestFilter {

    public static final String AUTH_HEADER = "Authorization";
    public static final String JWT_QUERY = "jwt";
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;
    private final ObjectMapper mapper;

    public JwtAuthFilter(JwtService jwtService, ObjectMapper mapper) {
        this.jwtService = jwtService;
        this.mapper = mapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI();

        // 非 /api/ 路径（页面、静态资源）放行
        if (!path.startsWith("/api/")) {
            chain.doFilter(request, response);
            return;
        }

        // auth 白名单 + 健康检查放行
        if (isWhitelisted(request, path)) {
            chain.doFilter(request, response);
            return;
        }

        String token = extractToken(request);
        if (token == null || token.isBlank()) {
            writeUnauthorized(response, "请先登录");
            return;
        }

        try {
            Claims claims = jwtService.parseToken(token);
            long userId = JwtService.userIdFrom(claims);
            String username = JwtService.usernameFrom(claims);
            // R2-T0：tid 是租户 claim；旧令牌无 tid 时回退 sub（userId）派生
            Long tenantId = JwtService.tenantIdFrom(claims);
            if (tenantId == null) {
                tenantId = userId;
            }
            UserContext.set(userId, username);
            TenantContext.set(tenantId);
            try {
                chain.doFilter(request, response);
            } finally {
                UserContext.clear();
                TenantContext.clear();
            }
        } catch (JwtException | IllegalArgumentException e) {
            writeUnauthorized(response, "令牌无效或已过期，请重新登录");
        }
    }

    private boolean isWhitelisted(HttpServletRequest request, String path) {
        // 健康检查
        if (path.startsWith("/api/health")) return true;
        // auth 白名单
        String method = request.getMethod();
        return ("POST".equalsIgnoreCase(method) && "/api/auth/register".equals(path))
                || ("POST".equalsIgnoreCase(method) && "/api/auth/login".equals(path));
    }

    private String extractToken(HttpServletRequest request) {
        // 优先从 Authorization header 取 Bearer token
        String auth = request.getHeader(AUTH_HEADER);
        if (auth != null && auth.startsWith(BEARER_PREFIX)) {
            return auth.substring(BEARER_PREFIX.length()).trim();
        }
        // SSE fallback：query 参数
        return request.getParameter(JWT_QUERY);
    }

    private void writeUnauthorized(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE + ";charset=UTF-8");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", "UNAUTHORIZED");
        body.put("message", message);
        mapper.writeValue(response.getWriter(), body);
    }
}
