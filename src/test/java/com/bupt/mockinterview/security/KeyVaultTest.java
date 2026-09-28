package com.bupt.mockinterview.security;

import com.bupt.mockinterview.llm.LlmProperties;
import com.bupt.mockinterview.rag.DashscopeProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 密钥保险箱单测（不起 Spring，{@code @TempDir} 隔离落盘）：
 * 机器绑定模式 / 密码模式 / 重启再加载 / 锁定解锁 / 增量更新与清除 / 环境变量优先不可覆盖。
 */
class KeyVaultTest {

    private static final String DS_KEY = "sk-deepseek-123";
    private static final String DASH_KEY = "sk-dashscope-456";

    @TempDir
    Path dir;

    private KeyVault newVault(LlmProperties llm, DashscopeProperties dash) {
        KeyVault v = new KeyVault(llm, dash, dir.toString());
        v.init(); // 模拟 @PostConstruct
        return v;
    }

    private KeyVault newEmptyVault() {
        return newVault(new LlmProperties(), new DashscopeProperties());
    }

    @Test
    void initialStatus_isNone_andKeysBlank() {
        KeyVault v = newEmptyVault();
        KeyVault.KeyStatus s = v.status();
        assertThat(s.deepseek()).isEqualTo("NONE");
        assertThat(s.dashscope()).isEqualTo("NONE");
        assertThat(s.passwordProtected()).isFalse();
        assertThat(s.unlocked()).isFalse();
        assertThat(v.deepseekKey()).isEmpty();
        assertThat(v.dashscopeKey()).isEmpty();
    }

    @Test
    void machineMode_saveThenRestart_autoLoadsKeys() throws Exception {
        KeyVault v = newEmptyVault();
        v.save(DS_KEY, DASH_KEY, null);

        assertThat(v.status().passwordProtected()).isFalse();
        assertThat(v.status().deepseek()).isEqualTo("VAULT");
        assertThat(v.deepseekKey()).isEqualTo(DS_KEY);
        assertThat(v.dashscopeKey()).isEqualTo(DASH_KEY);
        assertThat(Files.exists(dir.resolve("vault.json"))).isTrue();
        assertThat(Files.exists(dir.resolve("machine.key"))).isTrue();

        // 模拟重启：新实例从同目录加载，应自动解锁
        KeyVault restarted = newEmptyVault();
        assertThat(restarted.status().unlocked()).isTrue();
        assertThat(restarted.deepseekKey()).isEqualTo(DS_KEY);
        assertThat(restarted.dashscopeKey()).isEqualTo(DASH_KEY);
        // 密文落盘，明文不得出现在 vault.json 中
        assertThat(Files.readString(dir.resolve("vault.json"))).doesNotContain(DS_KEY, DASH_KEY);
    }

    @Test
    void passwordMode_restartLocks_unlockWithPassword() {
        KeyVault v = newEmptyVault();
        v.save(DS_KEY, DASH_KEY, "p@ssw0rd");
        assertThat(v.status().passwordProtected()).isTrue();
        assertThat(v.status().unlocked()).isTrue();

        // 重启后：保险箱存在但锁定，取不到 key
        KeyVault locked = newEmptyVault();
        KeyVault.KeyStatus s = locked.status();
        assertThat(s.passwordProtected()).isTrue();
        assertThat(s.unlocked()).isFalse();
        assertThat(s.deepseek()).isEqualTo("VAULT");
        assertThat(locked.deepseekKey()).isEmpty();

        // 错误密码 → 401
        assertThatThrownBy(() -> locked.unlock("wrong"))
                .isInstanceOf(KeyVaultException.class);
        assertThat(locked.status().unlocked()).isFalse();

        // 正确密码 → 解锁可取
        locked.unlock("p@ssw0rd");
        assertThat(locked.deepseekKey()).isEqualTo(DS_KEY);
        assertThat(locked.dashscopeKey()).isEqualTo(DASH_KEY);

        // 锁定后内存清空
        locked.lock();
        assertThat(locked.deepseekKey()).isEmpty();
        assertThat(locked.status().unlocked()).isFalse();
    }

    @Test
    void incrementalUpdate_keepOverwriteClear() {
        KeyVault v = newEmptyVault();
        v.save(DS_KEY, DASH_KEY, null);

        // null=不动、非空=覆盖、""=清除
        v.save(null, "sk-dash-new", null);
        assertThat(v.deepseekKey()).isEqualTo(DS_KEY);
        assertThat(v.dashscopeKey()).isEqualTo("sk-dash-new");

        v.save("", null, null);
        assertThat(v.deepseekKey()).isEmpty();
        assertThat(v.dashscopeKey()).isEqualTo("sk-dash-new");

        // 两个都空 → 拒绝
        assertThatThrownBy(() -> v.save("", "", null))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void envKey_takesPrecedence_andCannotBeOverwrittenFromVault() {
        LlmProperties llm = new LlmProperties();
        llm.setApiKey("env-deep-key");
        KeyVault v = newVault(llm, new DashscopeProperties());

        // 保险箱里存的是 DashScope，DeepSeek 来源仍为 ENV
        v.save(null, DASH_KEY, null);
        assertThat(v.status().deepseek()).isEqualTo("ENV");
        assertThat(v.deepseekKey()).isEqualTo("env-deep-key");

        // 页面试图覆盖环境变量提供的 key → 409
        assertThatThrownBy(() -> v.save("sk-page", null, null))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void passwordChange_oldPasswordFailsAfterRestart() {
        KeyVault v = newEmptyVault();
        v.save(DS_KEY, DASH_KEY, "old-pwd");

        // 解锁后不带密码改内容 → 沿用原密码；带新密码 → 换密码重加密
        v.unlock("old-pwd");
        v.save(null, null, "new-pwd");

        KeyVault restarted = newEmptyVault();
        assertThatThrownBy(() -> restarted.unlock("old-pwd"))
                .isInstanceOf(KeyVaultException.class);
        restarted.unlock("new-pwd");
        assertThat(restarted.deepseekKey()).isEqualTo(DS_KEY);
    }

    @Test
    void lockedPasswordVault_saveWithoutPassword_rejected() {
        KeyVault v = newEmptyVault();
        v.save(DS_KEY, DASH_KEY, "secret");

        KeyVault restarted = newEmptyVault(); // 锁定状态
        assertThatThrownBy(() -> restarted.save("sk-x", null, null))
                .isInstanceOf(IllegalStateException.class);
        // 带上正确密码则可改
        restarted.save("sk-x", null, "secret");
        assertThat(restarted.deepseekKey()).isEqualTo("sk-x");
    }

    // ---------- 访问门禁令牌（多设备/服务器场景，2026-09-19） ----------

    @Test
    void passwordMode_issuesTokensOnSaveAndUnlock_lockRevokes() {
        KeyVault v = newEmptyVault();

        // 首次「填密钥 + 设密码」：随保存给当前浏览器签令牌
        KeyVault.UnlockResult saved = v.save(DS_KEY, DASH_KEY, "secret");
        assertThat(saved.status().passwordProtected()).isTrue();
        assertThat(saved.token()).isNotBlank();
        assertThat(v.accessRequired()).isTrue();              // 密码模式 = 强制门禁
        assertThat(v.validToken(saved.token())).isTrue();
        assertThat(v.validToken(null)).isFalse();
        assertThat(v.validToken("forged")).isFalse();

        // 模拟服务重启：令牌不持久化，旧令牌在新进程里全部失效（保险箱仍是密码模式，门禁照拦）
        KeyVault restarted = newEmptyVault();
        assertThat(restarted.accessRequired()).isTrue();
        assertThat(restarted.validToken(saved.token())).isFalse();

        // 错误密码不签发
        assertThatThrownBy(() -> restarted.unlock("wrong"))
                .isInstanceOf(KeyVaultException.class);

        // 正确密码：新设备/刷新后重新拿令牌（多设备各自的令牌互不影响）
        KeyVault.UnlockResult unlocked = restarted.unlock("secret");
        assertThat(unlocked.token()).isNotBlank();
        assertThat(restarted.validToken(unlocked.token())).isTrue();

        // 锁定：全部令牌作废
        restarted.lock();
        assertThat(restarted.accessRequired()).isTrue();       // 仍是密码模式
        assertThat(restarted.validToken(unlocked.token())).isFalse();
    }

    @Test
    void passwordRotation_revokesOldToken_issuesNew() {
        KeyVault v = newEmptyVault();
        KeyVault.UnlockResult first = v.save(DS_KEY, DASH_KEY, "pwd-1");
        assertThat(v.validToken(first.token())).isTrue();

        // 改密码：旧令牌作废（其他设备需用新密码），返回当前浏览器的新令牌
        v.unlock("pwd-1");
        KeyVault.UnlockResult rotated = v.save(null, null, "pwd-2");
        assertThat(rotated.token()).isNotBlank();
        assertThat(rotated.token()).isNotEqualTo(first.token());
        assertThat(v.validToken(first.token())).isFalse();
        assertThat(v.validToken(rotated.token())).isTrue();

        // 密码模式下仅增量改密钥（不带 password）：不动令牌，返回 token=null，旧令牌继续有效
        KeyVault.UnlockResult incremental = v.save(null, "sk-dash-new", null);
        assertThat(incremental.token()).isNull();
        assertThat(v.validToken(rotated.token())).isTrue();
    }

    @Test
    void machineMode_noGateAndNoToken() {
        KeyVault v = newEmptyVault();
        // 机器绑定模式：本机免密形态，不启用访问门禁，也不签令牌
        KeyVault.UnlockResult saved = v.save(DS_KEY, DASH_KEY, null);
        assertThat(saved.token()).isNull();
        assertThat(v.accessRequired()).isFalse();

        // 重启后机器模式自动解锁，门禁依旧关闭
        KeyVault restarted = newEmptyVault();
        assertThat(restarted.accessRequired()).isFalse();
        assertThat(restarted.status().unlocked()).isTrue();
    }
}
