package com.bupt.mockinterview.billing;

import com.bupt.mockinterview.domain.Quota;
import com.bupt.mockinterview.domain.User;
import com.bupt.mockinterview.domain.repository.QuotaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 服务点额度（R1-3 最小骨架）。
 * <p>
 * PLATFORM 模式不扣点（R2 做 token 计费）；BYOK 模式每次 DeepSeek 调用前
 * {@link #chargeForByokCall(Long)} 扣配置点数，余额不足抛 {@link QuotaExhaustedException}（402）。
 */
@Service
public class QuotaService {

    private final QuotaRepository quotaRepo;
    private final QuotaProperties props;

    public QuotaService(QuotaRepository quotaRepo, QuotaProperties props) {
        this.quotaRepo = quotaRepo;
        this.props = props;
    }

    /** 注册事务内调用：为新用户建额度并赠送点数。 */
    @Transactional
    public Quota grantForNewUser(User user) {
        return quotaRepo.save(new Quota(user, props.getSignupBonus()));
    }

    /** 幂等补建（测试基座等历史/旁路场景）：已有额度不动，没有则按赠送点数建。 */
    @Transactional
    public Quota grantIfAbsent(User user) {
        return quotaRepo.findByUserId(user.getId())
                .orElseGet(() -> quotaRepo.save(new Quota(user, props.getSignupBonus())));
    }

    /**
     * BYOK 调用前扣点。条件更新保证不超发；返回 false = 余额不足（调用方转 402，不发起 LLM 调用）。
     */
    @Transactional
    public boolean chargeForByokCall(Long userId) {
        return quotaRepo.tryCharge(userId, props.getByokChargePerCall()) > 0;
    }

    /** 读用户额度（必有；注册时即建）。 */
    @Transactional(readOnly = true)
    public Quota getByUser(Long userId) {
        return quotaRepo.findByUserId(userId)
                .orElseThrow(() -> new IllegalStateException("额度账户不存在"));
    }
}
