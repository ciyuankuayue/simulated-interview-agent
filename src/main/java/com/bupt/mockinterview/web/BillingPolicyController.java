package com.bupt.mockinterview.web;

import com.bupt.mockinterview.billing.QuotaProperties;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController @RequestMapping("/api/user")
public class BillingPolicyController {
    private final QuotaProperties props;
    public BillingPolicyController(QuotaProperties props) { this.props = props; }
    @GetMapping("/billing-policy")
    public Map<String, Object> policy() {
        return Map.of("signupBonusPoints", props.getSignupBonusTokens() / (double)QuotaProperties.TOKENS_PER_POINT,
                "tokensPerPoint", QuotaProperties.TOKENS_PER_POINT,
                "byokTokensPerPoint", QuotaProperties.TOKENS_PER_POINT * (long)Math.max(1, props.getByokTokenDivisor()),
                "feedbackRewardPoints", 200, "billingVersion", QuotaProperties.BILLING_VERSION);
    }
}
