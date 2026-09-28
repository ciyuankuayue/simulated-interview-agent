package com.bupt.mockinterview.security;

import com.bupt.mockinterview.llm.LlmProperties;
import com.bupt.mockinterview.rag.DashscopeProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link VaultAccessFilter} 单测（不起 Spring）：密码模式门禁、白名单、header/query 双通道、
 * 锁定令牌失效、机器模式不启用门禁。
 */
class VaultAccessFilterTest {

    private static final String DS_KEY = "sk-deepseek-123";
    private static final String DASH_KEY = "sk-dashscope-456";

    @TempDir
    Path dir;

    private KeyVault passwordVault() {
        KeyVault v = new KeyVault(new LlmProperties(), new DashscopeProperties(), dir.toString());
        v.init();
        v.save(DS_KEY, DASH_KEY, "secret");
        return v;
    }

    private KeyVault machineVault() {
        KeyVault v = new KeyVault(new LlmProperties(), new DashscopeProperties(), dir.toString());
        v.init();
        v.save(DS_KEY, DASH_KEY, null);
        return v;
    }

    /** 过一次过滤器，返回「是否放行进 chain」。 */
    private boolean invoke(VaultAccessFilter filter, HttpServletRequest req, HttpServletResponse resp) throws Exception {
        AtomicBoolean proceeded = new AtomicBoolean(false);
        FilterChain chain = (request, response) -> proceeded.set(true);
        filter.doFilter(req, resp, chain);
        return proceeded.get();
    }

    private MockHttpServletRequest apiGet(String path) {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", path);
        req.setServletPath(path);
        return req;
    }

    @Test
    void passwordMode_businessApi_withoutToken_401Json() throws Exception {
        KeyVault vault = passwordVault();
        VaultAccessFilter filter = new VaultAccessFilter(vault, new ObjectMapper());

        MockHttpServletResponse resp = new MockHttpServletResponse();
        boolean proceeded = invoke(filter, apiGet("/api/jd"), resp);

        assertThat(proceeded).isFalse();
        assertThat(resp.getStatus()).isEqualTo(401);
        assertThat(resp.getContentAsString()).contains("UNAUTHORIZED");
        assertThat(resp.getContentType()).contains("application/json");
    }

    @Test
    void passwordMode_withHeaderToken_passes() throws Exception {
        KeyVault vault = passwordVault();
        String token = vault.unlock("secret").token();
        VaultAccessFilter filter = new VaultAccessFilter(vault, new ObjectMapper());

        MockHttpServletRequest req = apiGet("/api/jd");
        req.addHeader(VaultAccessFilter.TOKEN_HEADER, token);
        MockHttpServletResponse resp = new MockHttpServletResponse();

        assertThat(invoke(filter, req, resp)).isTrue();
        assertThat(resp.getStatus()).isEqualTo(200);
    }

    @Test
    void passwordMode_sseQueryToken_passes() throws Exception {
        KeyVault vault = passwordVault();
        String token = vault.unlock("secret").token();
        VaultAccessFilter filter = new VaultAccessFilter(vault, new ObjectMapper());

        // EventSource 不能设头，SSE 走 query 参数
        MockHttpServletRequest req = apiGet("/api/interview/42/events");
        req.setParameter(VaultAccessFilter.TOKEN_PARAM, token);
        MockHttpServletResponse resp = new MockHttpServletResponse();

        assertThat(invoke(filter, req, resp)).isTrue();
    }

    @Test
    void passwordMode_forgedOrLockedToken_401() throws Exception {
        KeyVault vault = passwordVault();
        String token = vault.unlock("secret").token();
        VaultAccessFilter filter = new VaultAccessFilter(vault, new ObjectMapper());

        MockHttpServletRequest forged = apiGet("/api/jd");
        forged.addHeader(VaultAccessFilter.TOKEN_HEADER, "not-a-real-token");
        assertThat(invoke(filter, forged, new MockHttpServletResponse())).isFalse();

        // 锁定后真令牌也立即失效
        vault.lock();
        MockHttpServletRequest stale = apiGet("/api/jd");
        stale.addHeader(VaultAccessFilter.TOKEN_HEADER, token);
        MockHttpServletResponse staleResp = new MockHttpServletResponse();
        assertThat(invoke(filter, stale, staleResp)).isFalse();
        assertThat(staleResp.getStatus()).isEqualTo(401);
    }

    @Test
    void passwordMode_whitelistAndStatic_passWithoutToken() throws Exception {
        KeyVault vault = passwordVault(); // 未解锁，门禁生效中
        VaultAccessFilter filter = new VaultAccessFilter(vault, new ObjectMapper());

        // 解锁协议：状态查询 + 密码换令牌
        assertThat(invoke(filter, apiGet("/api/setup/status"), new MockHttpServletResponse())).isTrue();
        MockHttpServletRequest unlockReq = new MockHttpServletRequest("POST", "/api/setup/unlock");
        unlockReq.setServletPath("/api/setup/unlock");
        assertThat(invoke(filter, unlockReq, new MockHttpServletResponse())).isTrue();

        // 页面与静态资源不拦（否则解锁页都打不开）
        assertThat(invoke(filter, apiGet("/"), new MockHttpServletResponse())).isTrue();
        assertThat(invoke(filter, apiGet("/app.js"), new MockHttpServletResponse())).isTrue();
        assertThat(invoke(filter, apiGet("/style.css"), new MockHttpServletResponse())).isTrue();

        // lock 接口不是白名单：没令牌不能调（防止匿名把服务锁住 DoS）
        MockHttpServletRequest lockReq = new MockHttpServletRequest("POST", "/api/setup/lock");
        lockReq.setServletPath("/api/setup/lock");
        MockHttpServletResponse lockResp = new MockHttpServletResponse();
        assertThat(invoke(filter, lockReq, lockResp)).isFalse();
        assertThat(lockResp.getStatus()).isEqualTo(401);
    }

    @Test
    void machineMode_gateDisabled_allPassWithoutToken() throws Exception {
        KeyVault vault = machineVault();
        assertThat(vault.accessRequired()).isFalse();
        VaultAccessFilter filter = new VaultAccessFilter(vault, new ObjectMapper());

        // 本机机器绑定模式：业务接口无需令牌
        assertThat(invoke(filter, apiGet("/api/jd"), new MockHttpServletResponse())).isTrue();
    }
}
