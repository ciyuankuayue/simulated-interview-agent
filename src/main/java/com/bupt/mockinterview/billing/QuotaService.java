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
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.LockModeType;

/**
 * Token 额度服务（R2）。
 * <ul>
 *   <li>PLATFORM 对话/embedding：调用前 {@link #ensureAvailable}，调用成功后 {@link #settlePlatformCall}
 *       按真实usage实扣；在额度行锁内计算余额限制后的实际扣减。</li>
 *   <li>BYOK 对话：预检余额，调用后按用量的10%结算服务 token 当量。</li>
 *   <li>扣减 + 流水 REQUIRES_NEW 独立提交：token 已真实消耗，业务事务后续回滚不退款。</li>
 * </ul>
 */
@Service
public class QuotaService {

    private final QuotaRepository quotaRepo;
    private final LlmUsageLogRepository usageRepo;
    private final QuotaProperties props;
    @PersistenceContext private EntityManager entityManager;
    @Autowired(required = false) private PlatformBudgetService platformBudget;

    public QuotaService(QuotaRepository quotaRepo, LlmUsageLogRepository usageRepo, QuotaProperties props) {
        this.quotaRepo = quotaRepo;
        this.usageRepo = usageRepo;
        this.props = props;
    }

    /**
     * 为新用户建额度并赠送 token。
     * <p>
     * R2-T0：固定 REQUIRES_NEW——注册/引导是<b>无身份白名单路径</b>，其首个 Hibernate Session
     * 必须在系统租户 0L 下创建（只操作不分区的 app_user）；调用方拿到新用户自增 id 并
     * {@code TenantContext.set(userId)} 后调用本方法，挂起旧 Session/事务，在<b>新建物理
     * Session</b> 中写入 quota（同一 Session 内租户只解析一次，事后改 ThreadLocal 无效）。
     * 独立提交也意味着赠额失败需由调用方补偿删除用户（见 AuthController.register）。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
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
     * PLATFORM/BYOK对话与embedding都走它；扣费在模型用量返回后结算。
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
    @Deprecated // Historical test/internal compatibility only; production LLM paths no longer invoke this.
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
        settle(userId, KeyMode.PLATFORM, scene, model, promptTokens, completionTokens, totalTokens, estimated);
    }

    /** BYOK now charges 10% of actual token usage after the call, never a fixed pre-call fee. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordByokChat(Long userId, UsageScene scene, String model,
                               long promptTokens, long completionTokens,
                               long totalTokens, boolean estimated) {
        settle(userId, KeyMode.BYOK, scene, model, promptTokens, completionTokens, totalTokens, estimated);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailedCall(Long userId, KeyMode mode, UsageScene scene, String model) {
        settle(userId, mode, scene, model, 0, 0, 0, true);
    }

    private void settle(Long userId, KeyMode mode, UsageScene scene, String model,
                        long promptTokens, long completionTokens, long totalTokens, boolean estimated) {
        if (totalTokens < 0 || promptTokens < 0 || completionTokens < 0) throw new IllegalArgumentException("用量不能为负数");
        String callId = UsageContext.callId() == null ? UUID.randomUUID().toString() : UsageContext.callId();
        Quota q = quotaRepo.lockByUserId(userId).orElseThrow(() -> new IllegalStateException("额度账户不存在"));
        // OSIV may already hold this entity: refresh under the lock before reading balances.
        entityManager.refresh(q, LockModeType.PESSIMISTIC_WRITE);
        if (usageRepo.existsByUserIdAndCallId(userId, callId)) return;
        int divisor = Math.max(1, props.getByokTokenDivisor());
        long due = mode == KeyMode.BYOK ? totalTokens / divisor + (totalTokens % divisor == 0 ? 0 : 1) : totalTokens;
        long charged = Math.min(due, q.remaining());
        q.setUsedTokens(q.getUsedTokens() + charged);
        LlmUsageLog row = new LlmUsageLog(userId, scene, mode, model, promptTokens, completionTokens,
                totalTokens, charged, estimated);
        row.setDueTokens(due);
        row.setCallId(callId);
        row.setSessionId(UsageContext.sessionId());
        row.setBillingVersion(QuotaProperties.BILLING_VERSION);
        row.setPricingVersion(ModelCosts.VERSION);
        row.setStatus(UsageContext.status());
        row.setAttemptCount(UsageContext.attempts());
        row.setPlatformCostMicros(mode == KeyMode.BYOK ? Long.valueOf(0L) :
                (totalTokens == 0 ? null : ModelCosts.estimateMicros(model, promptTokens, completionTokens, totalTokens)));
        usageRepo.save(row);
    }

    public int boundedOutputTokens(int requested) {
        return platformBudget == null ? requested : platformBudget.capOutput(requested);
    }

    public PlatformBudgetService.Reservation reservePlatformCall(String callId, String model,
                                                                  long inputTokenBound, int outputBound, int attempts) {
        return platformBudget == null ? null : platformBudget.reserve(callId, model, inputTokenBound, outputBound, attempts);
    }

    public void settlePlatformBudget(PlatformBudgetService.Reservation reservation, Long costMicros, int attempts) {
        if (platformBudget != null) platformBudget.settle(reservation, costMicros, attempts);
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
