package com.bupt.mockinterview.web;

import com.bupt.mockinterview.billing.QuotaService;
import com.bupt.mockinterview.domain.ChatProviderChoice;
import com.bupt.mockinterview.domain.KeyMode;
import com.bupt.mockinterview.domain.LlmUsageLog;
import com.bupt.mockinterview.domain.Quota;
import com.bupt.mockinterview.domain.User;
import com.bupt.mockinterview.domain.UserApiKey;
import com.bupt.mockinterview.domain.repository.UserApiKeyRepository;
import com.bupt.mockinterview.domain.repository.UserRepository;
import com.bupt.mockinterview.llm.ApiKeyStatus;
import com.bupt.mockinterview.llm.LlmPort;
import com.bupt.mockinterview.security.CurrentUserService;
import com.bupt.mockinterview.security.UserKeyCipher;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * R1-3 用户级「模型调用方式」配置（api.md §7）。
 * <ul>
 *   <li>PLATFORM（默认）：用平台 env key，R1-3 不扣点；</li>
 *   <li>BYOK：用户自带 DeepSeek key（AES-GCM 加密入库，明文永不回传），每次调用扣服务点。</li>
 * </ul>
 * embedding 永远走平台 key，不在此暴露。
 */
@RestController
@RequestMapping("/api/user")
public class UserController {

    private final CurrentUserService currentUser;
    private final UserRepository userRepo;
    private final UserApiKeyRepository apiKeyRepo;
    private final UserKeyCipher cipher;
    private final QuotaService quotaService;
    private final LlmPort llm;

    public UserController(CurrentUserService currentUser, UserRepository userRepo,
                          UserApiKeyRepository apiKeyRepo, UserKeyCipher cipher,
                          QuotaService quotaService, LlmPort llm) {
        this.currentUser = currentUser;
        this.userRepo = userRepo;
        this.apiKeyRepo = apiKeyRepo;
        this.cipher = cipher;
        this.quotaService = quotaService;
        this.llm = llm;
    }

    // ---------- 调用方式 ----------

    /** 当前设置：keyMode（PLATFORM / BYOK）+ chatProvider（DEEPSEEK / QWEN）。 */
    @GetMapping("/settings")
    public Map<String, Object> getSettings() {
        User user = currentUser.currentUser();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("keyMode", user.getKeyMode().name());
        body.put("chatProvider", user.getChatProvider().name());
        return body;
    }

    /**
     * 切换设置。两个字段均可选、各自独立更新（前端单选各自只 PUT 自己那项），
     * 但至少传一个，否则 400。PLATFORM 总是允许；切 BYOK 前必须已保存有效 key，否则 409 KEY_NOT_CONFIGURED。
     * chatProvider 与 keyMode 解耦：任何调用方式下都可选 QWEN（Qwen 恒走平台 key）。
     */
    @PutMapping("/settings")
    @Transactional
    public ResponseEntity<?> putSettings(@RequestBody SettingsRequest req) {
        boolean hasKeyMode = req.keyMode() != null && !req.keyMode().isBlank();
        boolean hasChatProvider = req.chatProvider() != null && !req.chatProvider().isBlank();
        if (!hasKeyMode && !hasChatProvider) {
            return error(HttpStatus.BAD_REQUEST, "INVALID_SETTINGS",
                    "至少传 keyMode 或 chatProvider 之一");
        }

        User user = currentUser.currentUser();

        if (hasKeyMode) {
            KeyMode mode;
            try {
                mode = KeyMode.valueOf(req.keyMode().trim());
            } catch (IllegalArgumentException e) {
                return error(HttpStatus.BAD_REQUEST, "INVALID_KEY_MODE",
                        "keyMode 只能是 PLATFORM 或 BYOK");
            }
            if (mode == KeyMode.BYOK && !apiKeyRepo.existsByUserId(user.getId())) {
                return error(HttpStatus.CONFLICT, "KEY_NOT_CONFIGURED",
                        "请先保存你的 DeepSeek API Key，再切换到 BYOK 模式");
            }
            user.setKeyMode(mode);
        }

        if (hasChatProvider) {
            ChatProviderChoice provider;
            try {
                provider = ChatProviderChoice.valueOf(req.chatProvider().trim());
            } catch (IllegalArgumentException e) {
                return error(HttpStatus.BAD_REQUEST, "INVALID_CHAT_PROVIDER",
                        "chatProvider 只能是 DEEPSEEK 或 QWEN");
            }
            user.setChatProvider(provider);
        }

        userRepo.save(user);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("keyMode", user.getKeyMode().name());
        resp.put("chatProvider", user.getChatProvider().name());
        return ResponseEntity.ok(resp);
    }

    // ---------- BYOK key ----------

    /** key 状态：只回传「有没有」+ 末 4 位，密文/明文一律不回传。 */
    @GetMapping("/api-key")
    public Map<String, Object> getApiKey() {
        return apiKeyRepo.findByUserId(currentUser.currentUserId())
                .map(row -> Map.<String, Object>of("hasKey", true, "last4", row.getKeyLast4()))
                .orElseGet(() -> Map.of("hasKey", false, "last4", ""));
    }

    /**
     * 保存/更换自带 key：先验活（GET /models，不烧对话 token），再 AES-GCM 加密 upsert。
     * 401/403 → 400 INVALID_API_KEY；网络/5xx → 502 PROVIDER_UNREACHABLE。
     * 保存本身不自动切 BYOK——前端随后显式 PUT /settings（也兼容用户先存好、稍后再切）。
     */
    @PutMapping("/api-key")
    @Transactional
    public ResponseEntity<?> putApiKey(@Valid @RequestBody ApiKeyRequest req) {
        String plain = req.apiKey().trim();
        if (!plain.startsWith("sk-")) {
            return error(HttpStatus.BAD_REQUEST, "INVALID_API_KEY",
                    "DeepSeek API Key 格式应以 sk- 开头");
        }

        ApiKeyStatus check = llm.verifyApiKey(plain);
        if (check == ApiKeyStatus.INVALID) {
            return error(HttpStatus.BAD_REQUEST, "INVALID_API_KEY", "API Key 无效或已被 DeepSeek 拒绝（401/403）");
        }
        if (check == ApiKeyStatus.UNREACHABLE) {
            return error(HttpStatus.BAD_GATEWAY, "PROVIDER_UNREACHABLE",
                    "DeepSeek 暂不可达（网络错误或服务端 5xx），key 未保存，请稍后重试");
        }

        User user = currentUser.currentUser();
        String ciphertext = cipher.encrypt(plain);
        String last4 = plain.substring(plain.length() - 4);
        UserApiKey row = apiKeyRepo.findByUserId(user.getId())
                .orElseGet(() -> new UserApiKey(user, ciphertext, last4));
        row.setKeyCiphertext(ciphertext);
        row.setKeyLast4(last4);
        apiKeyRepo.save(row);

        return ResponseEntity.ok(Map.of("hasKey", true, "last4", last4));
    }

    /**
     * 删除自带 key：强制回退 PLATFORM（避免出现「BYOK 模式但库里没 key」的死状态——
     * 那种状态下每次 LLM 调用都会 409）。
     */
    @DeleteMapping("/api-key")
    @Transactional
    public ResponseEntity<?> deleteApiKey() {
        User user = currentUser.currentUser();
        apiKeyRepo.deleteByUserId(user.getId());
        if (user.getKeyMode() == KeyMode.BYOK) {
            user.setKeyMode(KeyMode.PLATFORM);
            userRepo.save(user);
        }
        return ResponseEntity.ok(Map.of("hasKey", false, "keyMode", KeyMode.PLATFORM.name()));
    }

    // ---------- 额度 ----------

    /** token 额度：total/used/remaining（PLATFORM 按真实 token 扣；BYOK 对话按次服务费）。 */
    @GetMapping("/quota")
    public Map<String, Object> getQuota() {
        Quota q = quotaService.getByUser(currentUser.currentUserId());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("totalTokens", q.getTotalTokens());
        body.put("usedTokens", q.getUsedTokens());
        body.put("remainingTokens", q.remaining());
        return body;
    }

    /**
     * 本人最近用量流水（R2，api.md §7），按时间倒序。
     * limit 收口到 1..50（默认 50），防止一次拖全表。
     */
    @GetMapping("/usage")
    public List<Map<String, Object>> getUsage(@RequestParam(defaultValue = "50") int limit) {
        int bounded = Math.max(1, Math.min(50, limit));
        List<Map<String, Object>> resp = new ArrayList<>();
        for (LlmUsageLog l : quotaService.recentUsage(currentUser.currentUserId(), bounded)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", l.getId());
            m.put("scene", l.getScene());
            m.put("keyMode", l.getKeyMode());
            m.put("model", l.getModel());
            m.put("promptTokens", l.getPromptTokens());
            m.put("completionTokens", l.getCompletionTokens());
            m.put("totalTokens", l.getTotalTokens());
            m.put("chargedTokens", l.getChargedTokens());
            m.put("createdAt", l.getCreatedAt());
            resp.add(m);
        }
        return resp;
    }

    // ---- 工具 ----

    private ResponseEntity<Map<String, Object>> error(HttpStatus status, String code, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("message", message);
        return ResponseEntity.status(status).body(body);
    }

    // ---- 请求 DTO ----

    public record SettingsRequest(String keyMode, String chatProvider) {}

    public record ApiKeyRequest(
            @NotBlank @Size(min = 8, max = 200) String apiKey) {}
}
