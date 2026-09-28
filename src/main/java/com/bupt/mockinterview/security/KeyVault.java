package com.bupt.mockinterview.security;

import com.bupt.mockinterview.llm.LlmProperties;
import com.bupt.mockinterview.rag.DashscopeProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 模型密钥保险箱（2026-09-19 迭代）：让用户在页面填一次 DeepSeek / DashScope key 即可，
 * 不必每次启动在终端设环境变量。
 *
 * <h3>来源优先级（不可覆盖）</h3>
 * 环境变量 {@code DEEPSEEK_API_KEY} / {@code DASHSCOPE_API_KEY} 优先；环境变量已提供时，
 * 页面保存该厂商密钥会被拒绝（409），避免「页面存了一份但不生效」的困惑。
 * 环境变量缺失时才读保险箱。
 *
 * <h3>落盘与加密（vault.json，默认 {@code ~/.mock-interview/}）</h3>
 * 两种保护模式，密文统一 AES-256-GCM（12B IV / 128b tag）：
 * <ul>
 *   <li><b>密码模式</b>：KEK = PBKDF2-HMAC-SHA256(密码, 随机盐, 310000 轮, 256b)。
 *       服务重启后内存无密钥，必须在页面 {@code POST /api/setup/unlock} 解锁。</li>
 *   <li><b>机器绑定模式</b>（不设密码）：首次生成随机 32B DEK 存同目录 {@code machine.key}，
 *       重启自动解密。防肉眼、防只拷走 vault.json，不防本机恶意程序（本地单人工具的合理威胁模型）。</li>
 * </ul>
 *
 * 内存态仅在解锁/保存后短暂持有明文与 KEK；{@link #lock()} 立即清除。
 *
 * <h3>密码模式 = 访问门禁（多设备/服务器场景）</h3>
 * 密码不只保护磁盘文件：密码模式下，{@link #unlock(String)} 校验通过后会签发一个<b>访问令牌</b>
 * （256b 随机，仅存服务端内存），{@code VaultAccessFilter} 对所有业务接口强制校验
 * （header {@code X-Vault-Token}；SSE 走 query {@code ?vaultToken=}）。
 * 前端只把令牌放在 JS 内存里——<b>刷新页面 / 换设备 / 重启服务后都必须重新输入密码</b>，
 * 防止「同局域网另一台设备打开页面就白用我的 API」。{@link #lock()} 清空全部令牌（所有设备掉线）。
 * 机器绑定模式与纯环境变量模式不启用门禁（仅本机单人使用的形态）。
 */
@Component
public class KeyVault {

    private static final Logger log = LoggerFactory.getLogger(KeyVault.class);

    private static final String VAULT_FILE = "vault.json";
    private static final String MACHINE_KEY_FILE = "machine.key";
    private static final int VERSION = 1;
    private static final int PBKDF2_ITERATIONS = 310_000;
    private static final int IV_LEN = 12;
    private static final int SALT_LEN = 16;
    private static final int KEY_BITS = 256;
    private static final int GCM_TAG_BITS = 128;

    private final LlmProperties llmProps;
    private final DashscopeProperties dashProps;
    private final Path dir;
    private final ObjectMapper mapper = new ObjectMapper();
    private final SecureRandom random = new SecureRandom();

    /** 磁盘上的保管箱元数据（null=尚无落盘文件）。 */
    private volatile VaultMeta meta;
    /** 解密后的密钥对；null=未加载（密码模式锁定 / 从未保存）。 */
    private volatile Keys keys;
    /** 解锁后在本进程内缓存的 KEK（供「改密钥不换密码」时重加密）；lock 清除。 */
    private SecretKey sessionKek;
    /**
     * 已签发的访问令牌（密码模式门禁用）。每个解锁的浏览器/设备一枚，只存内存：
     * 重启服务或 {@link #lock()} 后全部失效——刷新页面/换设备必须重新输密码。
     */
    private final Set<String> accessTokens = ConcurrentHashMap.newKeySet();

    public KeyVault(LlmProperties llmProps, DashscopeProperties dashProps,
                    @Value("${app.vault.dir:${user.home}/.mock-interview}") String vaultDir) {
        this.llmProps = llmProps;
        this.dashProps = dashProps;
        this.dir = Paths.get(vaultDir);
    }

    @PostConstruct
    synchronized void init() {
        Path f = dir.resolve(VAULT_FILE);
        if (!Files.exists(f)) {
            return;
        }
        try {
            meta = mapper.readValue(Files.readString(f, StandardCharsets.UTF_8), VaultMeta.class);
            if (!meta.passwordProtected()) {
                // 机器绑定：用同目录 DEK 自动解锁
                decryptIntoMemory(meta, machineKek());
            }
            // 密码模式：保持锁定，等页面 unlock
        } catch (Exception e) {
            log.warn("密钥保险箱加载失败，按未配置处理（可在页面重新保存）: {}", e.getMessage());
            meta = null;
            keys = null;
        }
    }

    // ---------- 取密钥（env 优先，保险箱兜底） ----------

    /** DeepSeek 实际生效的 key：环境变量优先；否则取保险箱（未解锁返空串）。 */
    public String deepseekKey() {
        String env = llmProps.getApiKey();
        if (notBlank(env)) {
            return env;
        }
        Keys k = keys;
        return k == null ? "" : nullToEmpty(k.deepseekKey());
    }

    /** DashScope 实际生效的 key：同上。 */
    public String dashscopeKey() {
        String env = dashProps.getApiKey();
        if (notBlank(env)) {
            return env;
        }
        Keys k = keys;
        return k == null ? "" : nullToEmpty(k.dashscopeKey());
    }

    // ---------- 状态 ----------

    public KeyStatus status() {
        return new KeyStatus(sourceDeepseek(), sourceDashscope(),
                meta != null && meta.passwordProtected(), keys != null);
    }

    /** ENV=环境变量提供（页面不可改）；VAULT=保险箱里有（但密码模式可能尚未解锁）；NONE=无。 */
    private String sourceDeepseek() {
        if (notBlank(llmProps.getApiKey())) {
            return "ENV";
        }
        if (meta == null) {
            return "NONE";
        }
        if (keys != null && !notBlank(keys.deepseekKey())) {
            return "NONE";
        }
        return "VAULT";
    }

    private String sourceDashscope() {
        if (notBlank(dashProps.getApiKey())) {
            return "ENV";
        }
        if (meta == null) {
            return "NONE";
        }
        if (keys != null && !notBlank(keys.dashscopeKey())) {
            return "NONE";
        }
        return "VAULT";
    }

    // ---------- 保存 / 解锁 / 锁定 ----------

    /**
     * 保存（或增量更新）密钥。
     *
     * @param deepseekKey  null=保持原值不动；""=清除该厂商；非空=覆盖
     * @param dashscopeKey 同上
     * @param password     非空=设为/改为密码保护；null=沿用现状（已解锁的密码箱沿用原密码，
     *                     机器绑定箱沿用机器 DEK，首次保存则进入机器绑定模式）
     */
    public synchronized UnlockResult save(String deepseekKey, String dashscopeKey, String password) {
        if ("ENV".equals(sourceDeepseek()) && deepseekKey != null) {
            throw new IllegalStateException("DeepSeek 密钥由环境变量 DEEPSEEK_API_KEY 提供，页面不可覆盖");
        }
        if ("ENV".equals(sourceDashscope()) && dashscopeKey != null) {
            throw new IllegalStateException("DashScope 密钥由环境变量 DASHSCOPE_API_KEY 提供，页面不可覆盖");
        }

        Keys old = loadOldForRewrite(password);

        String newDeep = deepseekKey != null ? deepseekKey : (old == null ? "" : nullToEmpty(old.deepseekKey()));
        String newDash = dashscopeKey != null ? dashscopeKey : (old == null ? "" : nullToEmpty(old.dashscopeKey()));
        if (!notBlank(newDeep) && !notBlank(newDash)) {
            throw new IllegalStateException("两个密钥都为空，没有可保存的内容");
        }

        SecretKey kek;
        VaultFileBuilder builder;
        if (password != null && !password.isEmpty()) {
            byte[] salt = randomBytes(SALT_LEN);
            kek = pbkdf2(password, salt, PBKDF2_ITERATIONS);
            builder = VaultFileBuilder.password(salt, PBKDF2_ITERATIONS);
        } else if (meta != null && meta.passwordProtected()) {
            // 已解锁的密码箱、本次不带新密码 → 用会话中缓存的原 KEK 原样重加密
            if (sessionKek == null) {
                throw new IllegalStateException("保险箱已锁定，请先输入密码解锁");
            }
            kek = sessionKek;
            builder = VaultFileBuilder.password(decode(meta.salt()), meta.iterations());
        } else {
            kek = machineKek();
            builder = VaultFileBuilder.machine();
        }

        Keys next = new Keys(newDeep, newDash);
        writeVault(builder, kek, next);

        keys = next;
        sessionKek = kek;

        // 本次显式设置/更换了密码 → 旧令牌全部作废（其他设备需用新密码重解），给当前浏览器签新令牌；
        // 密码模式下仅增量改密钥（沿用原密码）→ 不动令牌集合，返回 token=null，前端继续用手里的令牌；
        // 机器绑定模式不启用门禁，token 恒为 null。
        String token = null;
        boolean passwordRotated = password != null && !password.isEmpty();
        if (passwordRotated) {
            accessTokens.clear();
            token = issueToken();
        }
        log.info("密钥已保存到保险箱（{}模式）", builder.passwordProtected ? "密码" : "机器绑定");
        return new UnlockResult(status(), token);
    }

    /**
     * 密码模式：重启后用密码解锁，校验通过后给该浏览器/设备签发一枚访问令牌（随响应返回，
     * 后续请求带 {@code X-Vault-Token}）。错误密码抛 {@link KeyVaultException}（401）。
     * 多设备各自解锁各自拿令牌，互不影响；不清空已有的其他令牌。
     */
    public synchronized UnlockResult unlock(String password) {
        if (meta == null) {
            throw new IllegalStateException("还没有保存过密钥");
        }
        if (!meta.passwordProtected()) {
            // 机器绑定箱本就不该锁：幂等重载一次（机器模式无门禁，token=null）
            decryptIntoMemory(meta, machineKek());
            return new UnlockResult(status(), null);
        }
        if (password == null || password.isEmpty()) {
            throw new KeyVaultException("请输入密码");
        }
        SecretKey kek = pbkdf2(password, decode(meta.salt()), meta.iterations());
        try {
            decryptIntoMemory(meta, kek);
        } catch (Exception e) {
            throw new KeyVaultException("密码错误，密钥未能解锁", e);
        }
        sessionKek = kek;
        return new UnlockResult(status(), issueToken());
    }

    /** 丢掉内存中的明文、KEK 与全部访问令牌（所有设备掉线；环境变量来源不受影响）。 */
    public synchronized KeyStatus lock() {
        keys = null;
        sessionKek = null;
        accessTokens.clear();
        return status();
    }

    // ---------- 访问门禁（密码模式） ----------

    /** 是否强制访问门禁：仅当保险箱存在且是密码保护模式（设了密码 = 要挡未输密码的设备）。 */
    public boolean accessRequired() {
        VaultMeta m = meta;
        return m != null && m.passwordProtected();
    }

    /** 校验浏览器/设备带来的访问令牌（header X-Vault-Token 或 SSE query vaultToken）。 */
    public boolean validToken(String token) {
        return token != null && !token.isEmpty() && accessTokens.contains(token);
    }

    private String issueToken() {
        byte[] b = new byte[32];
        random.nextBytes(b);
        String t = Base64.getUrlEncoder().withoutPadding().encodeToString(b);
        accessTokens.add(t);
        return t;
    }

    // ---------- 内部实现 ----------

    /**
     * 重写前取得旧密钥：
     * 已加载 → 内存；密码模式锁定 → 必须带密码当场解一次；无文件 → null。
     */
    private Keys loadOldForRewrite(String password) {
        if (keys != null) {
            return keys;
        }
        if (meta == null) {
            return null;
        }
        if (!meta.passwordProtected()) {
            decryptIntoMemory(meta, machineKek());
            return keys;
        }
        if (password == null || password.isEmpty()) {
            throw new IllegalStateException("保险箱已锁定，请先输入密码解锁后再修改");
        }
        SecretKey kek = pbkdf2(password, decode(meta.salt()), meta.iterations());
        try {
            decryptIntoMemory(meta, kek);
        } catch (Exception e) {
            throw new KeyVaultException("密码错误，密钥未能解锁", e);
        }
        sessionKek = kek;
        return keys;
    }

    private void decryptIntoMemory(VaultMeta m, SecretKey kek) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, kek, new GCMParameterSpec(GCM_TAG_BITS, decode(m.iv())));
            byte[] plain = cipher.doFinal(decode(m.ciphertext()));
            @SuppressWarnings("unchecked")
            Map<String, String> body = mapper.readValue(plain, Map.class);
            keys = new Keys(nullToEmpty(body.get("deepseekKey")), nullToEmpty(body.get("dashscopeKey")));
        } catch (Exception e) {
            throw new KeyVaultException("密钥解密失败（密码错误或保险箱文件损坏）", e);
        }
    }

    private void writeVault(VaultFileBuilder b, SecretKey kek, Keys payload) {
        try {
            Files.createDirectories(dir);
            byte[] iv = randomBytes(IV_LEN);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, kek, new GCMParameterSpec(GCM_TAG_BITS, iv));

            Map<String, String> body = new LinkedHashMap<>();
            body.put("deepseekKey", payload.deepseekKey());
            body.put("dashscopeKey", payload.dashscopeKey());
            byte[] ct = cipher.doFinal(mapper.writeValueAsBytes(body));

            VaultMeta out = new VaultMeta(VERSION, b.passwordProtected, b.iterations,
                    b.salt == null ? null : encode(b.salt), encode(iv), encode(ct));

            Path target = dir.resolve(VAULT_FILE);
            Path tmp = dir.resolve(VAULT_FILE + ".tmp");
            Files.writeString(tmp, mapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(out), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException atomicNotSupported) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            meta = out;
        } catch (IOException e) {
            throw new IllegalStateException("密钥保险箱写入失败: " + dir, e);
        } catch (Exception e) {
            throw new IllegalStateException("密钥加密失败: " + e.getMessage(), e);
        }
    }

    /** 机器绑定 DEK：读 {@code machine.key}，不存在则生成 32B 随机密钥落盘（尽量收紧为属主可读写）。 */
    private SecretKey machineKek() {
        try {
            Files.createDirectories(dir);
            Path p = dir.resolve(MACHINE_KEY_FILE);
            byte[] raw;
            if (Files.exists(p)) {
                raw = Files.readAllBytes(p);
                if (raw.length != KEY_BITS / 8) {
                    throw new IllegalStateException(MACHINE_KEY_FILE + " 长度异常（应为 32B），可能已损坏");
                }
            } else {
                raw = randomBytes(KEY_BITS / 8);
                Path tmp = dir.resolve(MACHINE_KEY_FILE + ".tmp");
                Files.write(tmp, raw);
                try {
                    Files.move(tmp, p, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException atomicNotSupported) {
                    Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING);
                }
                p.toFile().setReadable(false, false);
                p.toFile().setWritable(false, false);
                p.toFile().setReadable(true, true);
                p.toFile().setWritable(true, true);
            }
            return new SecretKeySpec(raw, "AES");
        } catch (IOException e) {
            throw new IllegalStateException("机器密钥不可读写: " + dir, e);
        }
    }

    private static SecretKey pbkdf2(String password, byte[] salt, int iterations) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, KEY_BITS);
            byte[] raw = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(spec).getEncoded();
            return new SecretKeySpec(raw, "AES");
        } catch (Exception e) {
            throw new IllegalStateException("PBKDF2 派生失败", e);
        }
    }

    private byte[] randomBytes(int n) {
        byte[] b = new byte[n];
        random.nextBytes(b);
        return b;
    }

    private static String encode(byte[] b) {
        return Base64.getEncoder().encodeToString(b);
    }

    private static byte[] decode(String s) {
        return Base64.getDecoder().decode(s);
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    // ---------- DTO / 落盘结构 ----------

    /** 对外状态（api.md §setup）：source ∈ ENV/VAULT/NONE；unlocked=保险箱内存当前可读。 */
    public record KeyStatus(String deepseek, String dashscope, boolean passwordProtected, boolean unlocked) {}

    /**
     * 解锁/保存成功的响应：状态 + 本次签发的访问令牌（仅密码模式非空）。
     * 令牌只随这一次响应下发，{@code GET /status} 等任何其他接口都不回传。
     */
    public record UnlockResult(KeyStatus status, String token) {}

    private record Keys(String deepseekKey, String dashscopeKey) {}

    /** vault.json 反序列化结构。 */
    private record VaultMeta(int version, boolean passwordProtected, int iterations,
                             String salt, String iv, String ciphertext) {}

    /** 写盘前的参数选择。 */
    private static final class VaultFileBuilder {
        private final boolean passwordProtected;
        private final int iterations;
        private final byte[] salt;

        private VaultFileBuilder(boolean passwordProtected, int iterations, byte[] salt) {
            this.passwordProtected = passwordProtected;
            this.iterations = iterations;
            this.salt = salt;
        }

        static VaultFileBuilder password(byte[] salt, int iterations) {
            return new VaultFileBuilder(true, iterations, salt);
        }

        static VaultFileBuilder machine() {
            return new VaultFileBuilder(false, 0, null);
        }
    }
}
