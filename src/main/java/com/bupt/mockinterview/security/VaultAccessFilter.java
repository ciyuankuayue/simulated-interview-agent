package com.bupt.mockinterview.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 密码模式的访问门禁（2026-09-19，多设备/服务器场景）。
 *
 * 启用条件：{@link KeyVault#accessRequired()}——保险箱落盘且为密码保护模式。
 * 启用后除白名单外，所有 {@code /api/**} 业务接口（含会花钱的 LLM/embedding 调用与全部个人数据）
 * 都必须携带有效访问令牌：请求头 {@code X-Vault-Token}；SSE 的 EventSource 无法自定义头，
 * 允许 query 参数 {@code ?vaultToken=}。缺失/失效 → 401 {@code {code,message}}。
 *
 * 白名单（不带令牌也要能访问，否则前端无法呈现解锁页）：
 * 页面与静态资源（非 {@code /api/} 路径）、{@code GET /api/setup/status}、{@code POST /api/setup/unlock}。
 *
 * 机器绑定模式 / 纯环境变量 / 尚未配置密钥时门禁不启用——那些是「仅本机使用」的形态。
 */
@Component
public class VaultAccessFilter extends OncePerRequestFilter {

    public static final String TOKEN_HEADER = "X-Vault-Token";
    public static final String TOKEN_PARAM = "vaultToken";

    private static final String STATUS_PATH = "/api/setup/status";
    private static final String UNLOCK_PATH = "/api/setup/unlock";

    private final KeyVault vault;
    private final ObjectMapper mapper;

    public VaultAccessFilter(KeyVault vault, ObjectMapper mapper) {
        this.vault = vault;
        this.mapper = mapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!vault.accessRequired()) {
            chain.doFilter(request, response);
            return;
        }

        String path = request.getRequestURI();
        // 页面 / 静态资源放行；CORS 预检放行（本地同源用不到，留作部署余量）
        if (!path.startsWith("/api/") || HttpMethod.OPTIONS.matches(request.getMethod())) {
            chain.doFilter(request, response);
            return;
        }

        // 解锁协议本身：查状态 + 用密码换令牌
        if ((STATUS_PATH.equals(path) && HttpMethod.GET.matches(request.getMethod()))
                || (UNLOCK_PATH.equals(path) && HttpMethod.POST.matches(request.getMethod()))) {
            chain.doFilter(request, response);
            return;
        }

        String token = request.getHeader(TOKEN_HEADER);
        if (token == null || token.isEmpty()) {
            token = request.getParameter(TOKEN_PARAM);   // SSE EventSource 只能走 query
        }
        if (vault.validToken(token)) {
            chain.doFilter(request, response);
            return;
        }

        writeUnauthorized(response);
    }

    private void writeUnauthorized(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE + ";charset=UTF-8");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", "UNAUTHORIZED");
        body.put("message", "请先输入密码解锁后再使用本系统");
        mapper.writeValue(response.getWriter(), body);
    }
}
