package com.bupt.mockinterview.billing;

import com.bupt.mockinterview.domain.KeyMode;
import com.bupt.mockinterview.domain.LlmUsageLog;
import com.bupt.mockinterview.domain.Quota;
import com.bupt.mockinterview.domain.User;
import com.bupt.mockinterview.domain.repository.LlmUsageLogRepository;
import com.bupt.mockinterview.domain.repository.QuotaRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Token 额度服务（R2，2026-10-02）。
 * <ul>
 *   <li>PLATFORM 对话/embedding：调用前 {@link #ensureAvailable}，调用成功后 {@link #settlePlatformCall}
 *       按真实 usage 实扣；tryCharge 不足（最后一次透支）→ clamp 到 total。</li>
 *   <li>BYOK 对话：调用前 {@link #chargeForByokCall} 扣固定服务费（条件更新，绝不超发），
 *       调用成功后 {@link #recordByokChat} 只落流水（真实 token 烧在用户 key 上，不再扣）。</li>
 *   <li>扣减 + 流水 REQUIRES_NEW 独立提交：token 已真实消耗，业务事务后续回滚不退款。</li>
 * </ul>
 */
@Service
public class QuotaService {

    private final QuotaRepository quotaRepo;
    private final LlmUsageLogRepository usageRepo;
    private final QuotaProperties props;

    public QuotaService(QuotaRepository quotaRepo, LlmUsageLogRepository usageRepo, QuotaProperties props) {
        this.quotaRepo = quotaRepo;
        this.usageRepo = usageRepo;
        this.props = props;
    }

    /** 注册事务内调用：为新用户建额度并赠送 token。 */
    @Transactional
    public Quota grantForNewUser(User user) {
        return quotaRepo.save(new Quota(user, props.getSignupBonusTokens()));
    }

    /** 幂等补建（测试基座等旁路场景）：已有额度不动，没有则按赠送额建。 */
    @Transactional
    public Quota grantIfAbsent(User user) {
        return quotaRepo.findByUserId(user.getId())
                .orElseGet(() -> quotaRepo.save(new Quota(user, props.getSignupBonusTokens())));
    }

    /**
     * 调用前预检：余额为 0 直接 402（不发起任何外部调用）。
     * PLATFORM 对话与 embedding 都走它；BYOK 对话走 {@link #chargeForByokCall}（预检+扣费一体）。
     */
    @Transactional(readOnly = true)
    public void ensureAvailable(Long userId, KeyMode mode) {
        if (getByUser(userId).remaining() <= 0L) {
            throw QuotaExhaustedException.forMode(mode);
        }
    }

    /**
     * BYOK 对话调用前扣固定服务费。条件更新保证不超发；
     * 返回 false = 余额不足（调用方转 402，不发起 LLM 调用，重试也不重复扣）。
     */
    @Transactional
    public boolean chargeForByokCall(Long userId) {
        return quotaRepo.tryChargeTokens(userId, props.getByokChargePerCall()) > 0;
    }

    /**
     * PLATFORM 调用（DeepSeek 对话 / DashScope embedding）成功后实扣 + 落流水。
     * 独立事务：外部调用已真实烧 token，即使外层业务事务回滚也不退款。
     *
     * @param totalTokens      厂商 usage.total_tokens（或估算兜底值）
     * @param estimated        true=usage 缺失、totalTokens 为估算值
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void settlePlatformCall(Long userId, UsageScene scene, String model,
                                   long promptTokens, long completionTokens,
                                   long totalTokens, boolean estimated) {
        if (totalTokens <= 0L) {
            // 理论上估算兜底不会给 0；防御：无用量不扣不记
            return;
        }
        if (quotaRepo.tryChargeTokens(userId, totalTokens) == 0) {
            // 最后一次调用超出剩余：透支部分不追讨，余额归零，之后预检拦死
            quotaRepo.clampUsedToTotal(userId);
        }
        usageRepo.save(new LlmUsageLog(userId, scene, KeyMode.PLATFORM, model,
                promptTokens, completionTokens, totalTokens, totalTokens, estimated));
    }

    /**
     * BYOK 对话成功后只落流水：服务费已在调用前扣除（{@link #chargeForByokCall}），
     * 真实 prompt/completion token 烧在用户自己的 key 上，仅记录展示。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordByokChat(Long userId, UsageScene scene, String model,
                               long promptTokens, long completionTokens,
                               long totalTokens, boolean estimated) {
        usageRepo.save(new LlmUsageLog(userId, scene, KeyMode.BYOK, model,
                promptTokens, completionTokens, totalTokens, props.getByokChargePerCall(), estimated));
    }

    /**
     * ADMIN 充值：增加总额度（grantTokens 在 controller 校验为正）。
     *
     * @return 充值后的最新额度
     */
    @Transactional
    public Quota grantTokens(Long userId, long grantTokens) {
        if (grantTokens <= 0L) {
            throw new IllegalArgumentException("充值额度必须为正数");
        }
        int rows = quotaRepo.addTotalTokens(userId, grantTokens);
        if (rows == 0) {
            throw new IllegalStateException("额度账户不存在");
        }
        return getByUser(userId);
    }

    /** 读用户额度（必有；注册时即建）。 */
    @Transactional(readOnly = true)
    public Quota getByUser(Long userId) {
        return quotaRepo.findByUserId(userId)
                .orElseThrow(() -> new IllegalStateException("额度账户不存在"));
    }

    /** 本人最近用量流水（limit 已在 controller 收口到 1..50）。 */
    @Transactional(readOnly = true)
    public List<LlmUsageLog> recentUsage(Long userId, int limit) {
        return usageRepo.findByUserIdOrderByIdDesc(userId, PageRequest.of(0, limit));
    }
}
