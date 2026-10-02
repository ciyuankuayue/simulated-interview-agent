package com.bupt.mockinterview.security;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 用户自带 API Key 的对称加密器（R1-3，2026-10-02）。
 *
 * <h3>密钥来源</h3>
 * KEK 取环境变量 {@code APP_DATA_KEY}（也可用配置 app.security.data-key）：
 * Base64 编码的 32 字节（AES-256）。未配置时启动自动生成临时 KEK 并打印 WARN——
 * 重启后旧密文无法解密（BYOK 用户需重新填写 key），仅为本地开发便利；
 * <b>多实例/生产部署必须显式配置同一 APP_DATA_KEY</b>，否则各实例密文互不可解。
 *
 * <h3>密文格式</h3>
 * Base64(12B IV || AES-GCM ciphertext+128b tag)，每次加密 IV 随机。
 */
@Component
public class UserKeyCipher {

    private static final Logger log = LoggerFactory.getLogger(UserKeyCipher.class);

    private static final String TRANSFORM = "AES/GCM/NoPadding";
    private static final int IV_LEN = 12;
    private static final int TAG_BITS = 128;
    private static final int KEY_BYTES = 32;

    private final String configuredKeyB64;
    private final SecureRandom random = new SecureRandom();
    private SecretKeySpec kek;

    public UserKeyCipher(@Value("${app.security.data-key:${APP_DATA_KEY:}}") String configuredKeyB64) {
        this.configuredKeyB64 = configuredKeyB64;
    }

    @PostConstruct
    void init() {
        if (configuredKeyB64 == null || configuredKeyB64.isBlank()) {
            byte[] raw = new byte[KEY_BYTES];
            random.nextBytes(raw);
            this.kek = new SecretKeySpec(raw, "AES");
            log.warn("APP_DATA_KEY 未配置：已生成临时数据加密密钥（重启后用户 BYOK 密文不可解，需重新填写）。"
                    + "生产/多实例必须显式设置同一 APP_DATA_KEY（Base64 32 字节）");
            return;
        }
        byte[] raw = Base64.getDecoder().decode(configuredKeyB64.trim());
        if (raw.length != KEY_BYTES) {
            throw new IllegalStateException("APP_DATA_KEY 必须是 Base64 编码的 32 字节（实际解码 " + raw.length + " 字节）");
        }
        this.kek = new SecretKeySpec(raw, "AES");
        log.info("用户数据加密密钥已从 APP_DATA_KEY 加载");
    }

    /** 加密明文 key → 可落库的 Base64 密文字符串。 */
    public String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isBlank()) {
            throw new IllegalArgumentException("待加密的 key 不能为空");
        }
        try {
            byte[] iv = new byte[IV_LEN];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORM);
            cipher.init(Cipher.ENCRYPT_MODE, kek, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = cipher.doFinal(plaintext.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new IllegalStateException("用户 key 加密失败: " + e.getMessage(), e);
        }
    }

    /** 解密 {@link #encrypt(String)} 产出的密文；密文损坏/KEK 不匹配会抛异常。 */
    public String decrypt(String stored) {
        if (stored == null || stored.isBlank()) {
            throw new IllegalArgumentException("密文不能为空");
        }
        try {
            byte[] all = Base64.getDecoder().decode(stored);
            if (all.length <= IV_LEN) {
                throw new IllegalArgumentException("密文长度异常");
            }
            byte[] iv = new byte[IV_LEN];
            System.arraycopy(all, 0, iv, 0, IV_LEN);
            byte[] ct = new byte[all.length - IV_LEN];
            System.arraycopy(all, IV_LEN, ct, 0, ct.length);
            Cipher cipher = Cipher.getInstance(TRANSFORM);
            cipher.init(Cipher.DECRYPT_MODE, kek, new GCMParameterSpec(TAG_BITS, iv));
            byte[] plain = cipher.doFinal(ct);
            return new String(plain, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("用户 key 解密失败（APP_DATA_KEY 已变更或密文损坏）: " + e.getMessage(), e);
        }
    }
}
