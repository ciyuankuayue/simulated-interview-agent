package com.bupt.mockinterview.operations;

import static com.bupt.mockinterview.operations.OperationDtos.*;
import static com.bupt.mockinterview.operations.OperationViews.*;

import com.bupt.mockinterview.domain.*;
import com.bupt.mockinterview.domain.repository.UserRepository;
import com.bupt.mockinterview.web.UserNotFoundException;

import jakarta.persistence.*;

import org.springframework.stereotype.Service;

import java.math.*;
import java.time.Instant;
import java.util.*;

@Service
public class OperationsService {
    @PersistenceContext private EntityManager em;
    private final OperationsTransactions tx;
    private final UserRepository users;

    public OperationsService(OperationsTransactions tx, UserRepository users) {
        this.tx = tx;
        this.users = users;
    }

    private void lock(Long id) {
        em.createQuery("select u from User u where u.id=:u", User.class)
                .setParameter("u", id)
                .setLockMode(LockModeType.PESSIMISTIC_WRITE)
                .getSingleResult();
    }

    private <T> T first(List<T> list) {
        return list.isEmpty() ? null : list.get(0);
    }

    private ProductFeedback own(Long userId) {
        return first(
                em.createQuery(
                                "select f from ProductFeedback f where f.userId=:u",
                                ProductFeedback.class)
                        .setParameter("u", userId)
                        .getResultList());
    }

    public Map<String, Object> feedback(Long uid) {
        return tx.tenant(
                uid,
                () -> {
                    var f = own(uid);
                    return map(
                            "feedback",
                            f == null ? null : OperationViews.feedback(f, null, false),
                            "rewardPoints",
                            200);
                });
    }

    public Map<String, Object> submit(Long uid, FeedbackRequest req, boolean supplement) {
        validateFeedback(req);
        return tx.tenant(
                uid,
                () -> {
                    lock(uid);
                    var f = own(uid);
                    if (f != null && !supplement)
                        throw new OperationException(409, "FEEDBACK_EXISTS", "你已提交反馈，可查看或补充原反馈");
                    if (supplement && f == null)
                        throw new OperationException(404, "FEEDBACK_NOT_FOUND", "尚未提交反馈");
                    if (f != null && Set.of("REWARDED", "CLOSED").contains(f.getStatus()))
                        throw new OperationException(409, "FEEDBACK_FINAL", "该反馈已处理");
                    if (f == null) {
                        f = new ProductFeedback();
                        f.setUserId(uid);
                        f.setOriginalExperience(req.experience().trim());
                    }
                    f.setProgress(req.progress());
                    f.setExperience(req.experience().trim());
                    f.setBlockers(String.join(",", req.blockers()));
                    f.setSuggestions(trim(req.suggestions()));
                    f.setFollowUp(req.followUp());
                    f.setContact(req.followUp() ? trim(req.contact()) : null);
                    f.setContactUpdatedAt(
                            req.followUp() && req.contact() != null && !req.contact().isBlank()
                                    ? Instant.now()
                                    : null);
                    f.setStatus("PENDING");
                    f.setReviewNote(null);
                    f.setUpdatedAt(Instant.now());
                    if (f.getId() == null) em.persist(f);
                    em.flush();
                    return OperationViews.feedback(f, null, false);
                });
    }

    private void validateFeedback(FeedbackRequest r) {
        if (!Set.of(
                        "LOOKED_ONLY",
                        "START_FAILED",
                        "STOPPED",
                        "INTERVIEW_DONE",
                        "REPORT_VIEWED",
                        "BUDDY",
                        "OTHER")
                .contains(r.progress())) throw new IllegalArgumentException("无效的体验阶段");
        Set<String> b = new HashSet<>(r.blockers());
        if (b.size() != r.blockers().size()
                || !Set.of(
                                "NONE",
                                "NO_NEED",
                                "CONFUSING",
                                "QUESTIONS",
                                "REPORT",
                                "SPEED",
                                "ERROR",
                                "COST",
                                "UNSURE",
                                "OTHER")
                        .containsAll(b)
                || ((b.contains("NONE") || b.contains("UNSURE")) && b.size() != 1))
            throw new IllegalArgumentException("原因选项重复或互斥");
    }

    private String trim(String s) {
        return s == null ? null : s.trim();
    }

    public Map<String, Object> preference(Long uid, PreferenceRequest req) {
        return tx.tenant(
                uid,
                () -> {
                    lock(uid);
                    var list =
                            em.createQuery(
                                            "select p from AnalyticsPreference p where p.userId=:u",
                                            AnalyticsPreference.class)
                                    .setParameter("u", uid)
                                    .getResultList();
                    AnalyticsPreference p = first(list);
                    if (p == null) {
                        p = new AnalyticsPreference();
                        p.setUserId(uid);
                        em.persist(p);
                    }
                    if (req != null) {
                        if (req.enabled() != null) p.setEnabled(req.enabled());
                        if (req.reportInvitationSeen() != null)
                            p.setReportInvitationSeen(req.reportInvitationSeen());
                    }
                    return map(
                            "enabled",
                            p.isEnabled(),
                            "reportInvitationSeen",
                            p.isReportInvitationSeen());
                });
    }

    public Map<String, Object> grant(Long operatorId, GrantRequest r) {
        User user =
                users.findByUsername(r.username())
                        .orElseThrow(() -> new UserNotFoundException("用户不存在"));
        long tokens = toTokens(r.points());
        if (!Set.of("GIFT", "PAID", "COMPENSATION", "ADJUSTMENT").contains(r.type()))
            throw new IllegalArgumentException("无效补额类型");
        if ("PAID".equals(r.type())) {
            if (r.amountCny() == null
                    || r.amountCny().signum() <= 0
                    || r.amountCny().scale() > 2
                    || r.amountCny().compareTo(new BigDecimal("9999999999.99")) > 0
                    || r.orderRef() == null
                    || r.orderRef().isBlank())
                throw new IllegalArgumentException("付费补额须填写正数金额和订单参考");
        } else if (r.amountCny() != null && r.amountCny().signum() != 0)
            throw new IllegalArgumentException("非付费补额不能记录收入");
        // Global idempotency check is an explicitly scoped ADMIN lookup over registered owner IDs.
        var prior =
                tx.root(
                        () -> {
                            List<Long> ids = ownerIds();
                            return ids.isEmpty()
                                    ? null
                                    : first(
                                            em.createQuery(
                                                            "select g from OperationGrant g where"
                                                                    + " g.userId in :owners and"
                                                                    + " g.requestId=:r",
                                                            OperationGrant.class)
                                                    .setParameter("owners", ids)
                                                    .setParameter("r", r.requestId())
                                                    .getResultList());
                        });
        if (prior != null && !prior.getUserId().equals(user.getId()))
            throw new OperationException(409, "REQUEST_CONFLICT", "请求号已用于其他用户");
        return tx.tenant(
                user.getId(),
                () -> {
                    lock(user.getId());
                    OperationGrant existing =
                            first(
                                    em.createQuery(
                                                    "select g from OperationGrant g where"
                                                            + " g.userId=:u and g.requestId=:r",
                                                    OperationGrant.class)
                                            .setParameter("u", user.getId())
                                            .setParameter("r", r.requestId())
                                            .getResultList());
                    if (existing != null) {
                        if (existing.getGrantTokens() != tokens
                                || !existing.getType().equals(r.type())
                                || !existing.getReason().equals(r.reason().trim())
                                || !Objects.equals(existing.getOrderRef(), trim(r.orderRef()))
                                || !moneyEquals(existing.getAmountCny(), r.amountCny()))
                            throw new OperationException(409, "REQUEST_CONFLICT", "请求号与原补额内容不一致");
                        return grantResult(existing, user.getUsername());
                    }
                    OperationGrant g =
                            createGrant(
                                    user.getId(),
                                    operatorId,
                                    tokens,
                                    r.type(),
                                    r.reason().trim(),
                                    r.amountCny(),
                                    trim(r.orderRef()),
                                    r.requestId());
                    return grantResult(g, user.getUsername());
                });
    }

    private boolean moneyEquals(BigDecimal a, BigDecimal b) {
        return a == null ? b == null || b.signum() == 0 : b != null && a.compareTo(b) == 0;
    }

    private long toTokens(BigDecimal p) {
        try {
            long n = p.multiply(BigDecimal.valueOf(500)).longValueExact();
            if (n <= 0 || n > 500000000L) throw new IllegalArgumentException("补充积分超出范围");
            return n;
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("积分最多精确到0.002");
        }
    }

    OperationGrant createGrant(
            Long uid,
            Long operator,
            long tokens,
            String type,
            String reason,
            BigDecimal money,
            String order,
            String request) {
        int n =
                em.createQuery(
                                "update Quota q set q.totalTokens=q.totalTokens+:n where"
                                        + " q.user.id=:u")
                        .setParameter("n", tokens)
                        .setParameter("u", uid)
                        .executeUpdate();
        if (n != 1) throw new OperationException(409, "QUOTA_MISSING", "用户积分账户不存在");
        OperationGrant g = new OperationGrant();
        g.setUserId(uid);
        g.setOperatorId(operator);
        g.setGrantTokens(tokens);
        g.setType(type);
        g.setReason(reason);
        g.setAmountCny(money);
        g.setOrderRef(order);
        g.setRequestId(request);
        em.persist(g);
        em.flush();
        return g;
    }

    private Map<String, Object> grantResult(OperationGrant g, String username) {
        Quota q =
                em.createQuery("select q from Quota q where q.user.id=:u", Quota.class)
                        .setParameter("u", g.getUserId())
                        .getSingleResult();
        em.refresh(q);
        return map(
                "grant",
                OperationViews.grant(g, username, true),
                "quota",
                map(
                        "totalTokens",
                        q.getTotalTokens(),
                        "usedTokens",
                        q.getUsedTokens(),
                        "remainingTokens",
                        q.remaining()));
    }

    public Map<String, Object> legacyGrant(Long operator, String username, long tokens) {
        User user =
                users.findByUsername(username)
                        .orElseThrow(() -> new UserNotFoundException("用户不存在"));
        return tx.tenant(
                user.getId(),
                () -> {
                    lock(user.getId());
                    return (Map<String, Object>)
                            grantResult(
                                            createGrant(
                                                    user.getId(),
                                                    operator,
                                                    tokens,
                                                    "LEGACY",
                                                    "旧管理接口补额",
                                                    null,
                                                    null,
                                                    "legacy:" + UUID.randomUUID()),
                                            username)
                                    .get("quota");
                });
    }

    public Map<String, Object> grants(Long uid, int page, int size) {
        page(page);
        size(size);
        return tx.tenant(
                uid,
                () -> {
                    var list =
                            em.createQuery(
                                            "select g from OperationGrant g where g.userId=:u order"
                                                    + " by g.id desc",
                                            OperationGrant.class)
                                    .setParameter("u", uid)
                                    .setFirstResult(page * size)
                                    .setMaxResults(size)
                                    .getResultList();
                    long count =
                            em.createQuery(
                                            "select count(g) from OperationGrant g where"
                                                    + " g.userId=:u",
                                            Long.class)
                                    .setParameter("u", uid)
                                    .getSingleResult();
                    return OperationViews.page(
                            list.stream().map(g -> OperationViews.grant(g, null, false)).toList(),
                            page,
                            size,
                            count);
                });
    }

    List<Long> ownerIds() {
        return em.createQuery("select u.id from User u", Long.class).getResultList();
    } // Unpartitioned account enumeration; ADMIN-only callers.

    public Map<String, Object> review(Long operator, Long feedbackId, ReviewRequest req) {
        if (!Set.of("REWARD", "NEEDS_INFO", "CLOSE").contains(req.action()))
            throw new IllegalArgumentException("无效审核动作");
        if ("NEEDS_INFO".equals(req.action()) && (req.note() == null || req.note().isBlank()))
            throw new IllegalArgumentException("请填写需要补充的内容");
        Long uid =
                tx.root(
                        () -> {
                            var ids = ownerIds();
                            if (ids.isEmpty()) return null;
                            return first(
                                    em.createQuery(
                                                    "select f.userId from ProductFeedback f where"
                                                            + " f.userId in :owners and f.id=:id",
                                                    Long.class)
                                            .setParameter("owners", ids)
                                            .setParameter("id", feedbackId)
                                            .getResultList());
                        });
        if (uid == null) throw new OperationException(404, "FEEDBACK_NOT_FOUND", "反馈不存在");
        return tx.tenant(
                uid,
                () -> {
                    lock(uid);
                    var f = own(uid);
                    if ("REWARDED".equals(f.getStatus())) {
                        if (!"REWARD".equals(req.action()))
                            throw new OperationException(409, "FEEDBACK_FINAL", "已奖励反馈不能修改");
                        var g =
                                em.createQuery(
                                                "select g from OperationGrant g where g.userId=:u"
                                                        + " and g.id=:id",
                                                OperationGrant.class)
                                        .setParameter("u", uid)
                                        .setParameter("id", f.getRewardGrantId())
                                        .getSingleResult();
                        return map(
                                "feedback",
                                OperationViews.feedback(f, username(uid), true),
                                "grant",
                                OperationViews.grant(g, username(uid), true));
                    }
                    if ("CLOSED".equals(f.getStatus()))
                        throw new OperationException(409, "FEEDBACK_FINAL", "已关闭反馈不能修改");
                    OperationGrant g = null;
                    if ("REWARD".equals(req.action())) {
                        g =
                                createGrant(
                                        uid,
                                        operator,
                                        100000,
                                        "FEEDBACK",
                                        "首次体验反馈奖励",
                                        null,
                                        null,
                                        "feedback:" + f.getId());
                        f.setRewardGrantId(g.getId());
                        f.setStatus("REWARDED");
                    } else f.setStatus("NEEDS_INFO".equals(req.action()) ? "NEEDS_INFO" : "CLOSED");
                    f.setReviewNote(trim(req.note()));
                    f.setReviewerId(operator);
                    f.setUpdatedAt(Instant.now());
                    return map(
                            "feedback",
                            OperationViews.feedback(f, username(uid), true),
                            "grant",
                            g == null ? null : OperationViews.grant(g, username(uid), true));
                });
    }

    String username(Long uid) {
        return em.createQuery("select u.username from User u where u.id=:u", String.class)
                .setParameter("u", uid)
                .getSingleResult();
    }
}
