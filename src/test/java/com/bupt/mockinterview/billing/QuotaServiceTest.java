package com.bupt.mockinterview.billing;

import com.bupt.mockinterview.domain.Quota;
import com.bupt.mockinterview.domain.User;
import com.bupt.mockinterview.domain.repository.QuotaRepository;
import com.bupt.mockinterview.domain.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R1-3 扣点防超发：真实 JPA + H2 跑条件更新 SQL，验证余额耗尽边界（tryCharge 返回 0 且 used 不被推穿）。
 */
@SpringBootTest
@Transactional
class QuotaServiceTest {

    @Autowired
    private QuotaService quotaService;
    @Autowired
    private QuotaRepository quotaRepo;
    @Autowired
    private UserRepository userRepo;

    @Test
    void chargeNeverOverdrafts_andGrantIfAbsentIsIdempotent() {
        User user = userRepo.save(new User("quota-edge-user", "hash"));
        Quota quota = quotaRepo.save(new Quota(user, 2));

        assertThat(quotaService.chargeForByokCall(user.getId())).isTrue();
        assertThat(quotaService.getByUser(user.getId()).remaining()).isEqualTo(1);

        assertThat(quotaService.chargeForByokCall(user.getId())).isTrue();
        assertThat(quotaService.getByUser(user.getId()).remaining()).isZero();

        // 第三次：条件更新 where 不命中 → 0 行，used 仍为 2（不会出现负数）
        assertThat(quotaService.chargeForByokCall(user.getId())).isFalse();
        Quota reread = quotaRepo.findByUserId(user.getId()).orElseThrow();
        assertThat(reread.getUsedPoints()).isEqualTo(2);
        assertThat(reread.remaining()).isZero();

        // 幂等补建：已有额度时不动 total
        Quota same = quotaService.grantIfAbsent(user);
        assertThat(same.getTotalPoints()).isEqualTo(2);
    }

    @Test
    void grantForNewUser_givesConfiguredSignupBonus() {
        User user = userRepo.save(new User("quota-bonus-user", "hash"));
        Quota quota = quotaService.grantForNewUser(user);
        assertThat(quota.getTotalPoints()).isEqualTo(1000L);
        assertThat(quota.getUsedPoints()).isZero();
    }
}
