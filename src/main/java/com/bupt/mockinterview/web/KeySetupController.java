package com.bupt.mockinterview.web;

import com.bupt.mockinterview.security.KeyVault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 模型密钥设置（api.md §setup，2026-09-19 迭代）。
 *
 * 用户在页面填一次 DeepSeek / DashScope key 即可，不必每次启动在终端设环境变量。
 * 保险箱细节见 {@link KeyVault}；本控制器只做协议适配，且<b>任何接口都不回传密钥明文</b>。
 *
 * 状态码：密码错误 401（{@link com.bupt.mockinterview.security.KeyVaultException}）；
 * 环境变量冲突 / 已锁定未带密码等状态冲突 409（IllegalStateException）。
 */
@RestController
@RequestMapping("/api/setup")
public class KeySetupController {

    private final KeyVault vault;

    public KeySetupController(KeyVault vault) {
        this.vault = vault;
    }

    /** 密钥配置状态：source ∈ ENV（环境变量提供，页面只读）/ VAULT（保险箱）/ NONE。 */
    @GetMapping("/status")
    public KeyVault.KeyStatus status() {
        return vault.status();
    }

    /**
     * 保存/增量更新。密码模式下随响应签发访问令牌（{@link KeyVault.UnlockResult#token()}），
     * 前端只存 JS 内存，后续请求带 X-Vault-Token；刷新/换设备需重新 {@code /unlock}。
     * 字段语义：deepseekKey/dashscopeKey 传 null=不动，传 ""=清除，传非空=覆盖。
     * password 非空=设为/改为密码保护（旧令牌作废）；不传=沿用现状（首次保存则进入机器绑定模式，重启自动可用）。
     */
    @PostMapping("/keys")
    public KeyVault.UnlockResult saveKeys(@RequestBody SaveKeysRequest req) {
        if (req == null) {
            throw new IllegalArgumentException("请求体不能为空");
        }
        return vault.save(req.deepseekKey(), req.dashscopeKey(), req.password());
    }

    /** 密码模式重启后 / 刷新页面 / 新设备解锁（错误密码 → 401）；成功返回状态 + 访问令牌。 */
    @PostMapping("/unlock")
    public KeyVault.UnlockResult unlock(@RequestBody UnlockRequest req) {
        return vault.unlock(req == null ? null : req.password());
    }

    /** 锁定：清除内存中的密钥明文、KEK 与全部访问令牌（所有设备掉线；环境变量来源不受影响）。 */
    @PostMapping("/lock")
    public KeyVault.KeyStatus lock() {
        return vault.lock();
    }

    public record SaveKeysRequest(String deepseekKey, String dashscopeKey, String password) {}

    public record UnlockRequest(String password) {}
}
