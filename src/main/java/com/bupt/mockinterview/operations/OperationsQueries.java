package com.bupt.mockinterview.operations;

import static com.bupt.mockinterview.operations.OperationViews.*;

import com.bupt.mockinterview.domain.*;

import jakarta.persistence.*;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

/**
 * Only ADMIN controller calls global queries; every partitioned query scopes explicit registered
 * owner IDs.
 */
@Service
public class OperationsQueries {
    @PersistenceContext private EntityManager em;
    private final OperationsTransactions tx;
    private static final Set<String> CORE =
            Set.of(
                    "IMPORT_SUCCEEDED",
                    "INTERVIEW_STARTED",
                    "ANSWER_SAVED",
                    "REPORT_GENERATED",
                    "BUDDY_SUCCEEDED");

    public OperationsQueries(OperationsTransactions tx) {
        this.tx = tx;
    }

    private Instant since(int days) {
        if (days < 1 || days > 90) throw new IllegalArgumentException("days应为1至90");
        return LocalDate.now(ZoneId.of("Asia/Shanghai"))
                .minusDays(days - 1)
                .atStartOfDay(ZoneId.of("Asia/Shanghai"))
                .toInstant();
    }

    private List<Long> ids() {
        return em.createQuery("select u.id from User u", Long.class).getResultList();
    } // Explicit ADMIN owner enumeration whitelist, app_user is not partitioned.

    private long count(String entity, String owner, String extra, List<Long> ids, Instant start) {
        if (ids.isEmpty()) return 0;
        var q =
                em.createQuery(
                                "select count(x) from "
                                        + entity
                                        + " x where x."
                                        + owner
                                        + " in :owners"
                                        + extra,
                                Long.class)
                        .setParameter("owners", ids);
        if (extra.contains(":since")) q.setParameter("since", start);
        return q.getSingleResult();
    }

    private long sum(String property, List<Long> ids, Instant start) {
        if (ids.isEmpty()) return 0;
        Long n =
                em.createQuery(
                                "select sum(l."
                                        + property
                                        + ") from LlmUsageLog l where l.userId in :owners and"
                                        + " l.createdAt>=:since",
                                Long.class)
                        .setParameter("owners", ids)
                        .setParameter("since", start)
                        .getSingleResult();
        return n == null ? 0 : n;
    }

    private BigDecimal cost(long micros) {
        return BigDecimal.valueOf(micros, 6);
    }

    public Map<String, Object> overview(int days) {
        Instant start = since(days);
        return tx.root(
                () -> {
                    var ids = ids();
                    if (ids.isEmpty())
                        return map(
                                "days",
                                days,
                                "registeredUsers",
                                0,
                                "activeUsers",
                                0,
                                "interviewsStarted",
                                0,
                                "reportsGenerated",
                                0,
                                "reportsViewed",
                                0,
                                "chargedPoints",
                                0,
                                "platformEstimatedCostCny",
                                0,
                                "paidAmountCny",
                                0,
                                "pendingFeedback",
                                0);
                    long regs =
                            em.createQuery(
                                            "select count(u) from User u where u.createdAt>=:s",
                                            Long.class)
                                    .setParameter("s", start)
                                    .getSingleResult();
                    long active =
                            em.createQuery(
                                            "select count(distinct e.userId) from ProductEvent e"
                                                + " where e.userId in :owners and"
                                                + " e.createdAt>=:since and e.serverEvent=true and"
                                                + " e.name in :core",
                                            Long.class)
                                    .setParameter("owners", ids)
                                    .setParameter("since", start)
                                    .setParameter("core", CORE)
                                    .getSingleResult();
                    long viewed =
                            em.createQuery(
                                            "select count(distinct e.sessionId) from ProductEvent e"
                                                + " where e.userId in :owners and"
                                                + " e.createdAt>=:since and e.name='REPORT_VIEW'",
                                            Long.class)
                                    .setParameter("owners", ids)
                                    .setParameter("since", start)
                                    .getSingleResult();
                    BigDecimal paid =
                            em.createQuery(
                                            "select sum(g.amountCny) from OperationGrant g where"
                                                + " g.userId in :owners and g.createdAt>=:since and"
                                                + " g.type='PAID'",
                                            BigDecimal.class)
                                    .setParameter("owners", ids)
                                    .setParameter("since", start)
                                    .getSingleResult();
                    return map(
                            "days",
                            days,
                            "budget",
                            budget(),
                            "registeredUsers",
                            regs,
                            "activeUsers",
                            active,
                            "interviewsStarted",
                            count(
                                    "InterviewSession",
                                    "user.id",
                                    " and x.createdAt>=:since",
                                    ids,
                                    start),
                            "reportsGenerated",
                            count(
                                    "CriticReport",
                                    "user.id",
                                    " and x.createdAt>=:since",
                                    ids,
                                    start),
                            "reportsViewed",
                            viewed,
                            "chargedPoints",
                            points(sum("chargedTokens", ids, start)),
                            "platformEstimatedCostCny",
                            cost(sum("platformCostMicros", ids, start)),
                            "paidAmountCny",
                            paid == null ? BigDecimal.ZERO : paid,
                            "unknownPlatformCostCalls",
                            unknownCost(ids, start),
                            "pendingFeedback",
                            count(
                                    "ProductFeedback",
                                    "userId",
                                    " and x.status='PENDING'",
                                    ids,
                                    start));
                });
    }

    public Map<String, Object> users(String search, int page, int size) {
        page(page);
        size(size);
        if (search.length() > 64) throw new IllegalArgumentException("搜索内容过长");
        return tx.root(
                () -> {
                    String pattern =
                            "%"
                                    + search.trim()
                                            .toLowerCase(Locale.ROOT)
                                            .replace("!", "!!")
                                            .replace("%", "!%")
                                            .replace("_", "!_")
                                    + "%";
                    var rows =
                            em.createQuery(
                                            "select u from User u where lower(u.username) like :q"
                                                    + " escape '!' order by u.id desc",
                                            User.class)
                                    .setParameter("q", pattern)
                                    .setFirstResult(page * size)
                                    .setMaxResults(size)
                                    .getResultList();
                    long total =
                            em.createQuery(
                                            "select count(u) from User u where lower(u.username)"
                                                    + " like :q escape '!'",
                                            Long.class)
                                    .setParameter("q", pattern)
                                    .getSingleResult();
                    return OperationViews.page(
                            rows.stream().map(this::userView).toList(), page, size, total);
                });
    }

    private Map<String, Object> userView(User u) {
        Long uid = u.getId();
        var qs =
                em.createQuery("select q from Quota q where q.user.id=:u", Quota.class)
                        .setParameter("u", uid)
                        .getResultList();
        Quota q = qs.isEmpty() ? null : qs.get(0);
        Instant last =
                em.createQuery(
                                "select max(e.createdAt) from ProductEvent e where e.userId=:u",
                                Instant.class)
                        .setParameter("u", uid)
                        .getSingleResult();
        Long token =
                em.createQuery(
                                "select sum(l.totalTokens) from LlmUsageLog l where l.userId=:u and"
                                        + " l.createdAt>=:s",
                                Long.class)
                        .setParameter("u", uid)
                        .setParameter("s", since(7))
                        .getSingleResult();
        return map(
                "id",
                uid,
                "username",
                u.getUsername(),
                "createdAt",
                u.getCreatedAt(),
                "lastActiveAt",
                last,
                "chatProvider",
                u.getChatProvider(),
                "keyMode",
                u.getKeyMode(),
                "effectiveKeyMode",
                u.getChatProvider() == ChatProviderChoice.QWEN ? KeyMode.PLATFORM : u.getKeyMode(),
                "remainingPoints",
                q == null ? 0 : points(q.remaining()),
                "usedPoints",
                q == null ? 0 : points(q.getUsedTokens()),
                "totalPoints",
                q == null ? 0 : points(q.getTotalTokens()),
                "sevenDayTokens",
                token == null ? 0 : token,
                "interviewCount",
                count("InterviewSession", "user.id", "", List.of(uid), null),
                "reportCount",
                count("CriticReport", "user.id", "", List.of(uid), null));
    }

    private String username(Long uid) {
        return em.createQuery("select u.username from User u where u.id=:u", String.class)
                .setParameter("u", uid)
                .getSingleResult();
    }

    private Map<String, Object> grantView(OperationGrant g) {
        return OperationViews.grant(g, username(g.getUserId()), true);
    }

    public Map<String, Object> grants(Long uid, int page, int size) {
        page(page);
        size(size);
        return tx.root(
                () -> {
                    List<Long> owners = uid == null ? ids() : List.of(uid);
                    if (owners.isEmpty()) return OperationViews.page(List.of(), page, size, 0);
                    var rows =
                            em.createQuery(
                                            "select g from OperationGrant g where g.userId in"
                                                    + " :owners order by g.id desc",
                                            OperationGrant.class)
                                    .setParameter("owners", owners)
                                    .setFirstResult(page * size)
                                    .setMaxResults(size)
                                    .getResultList();
                    return OperationViews.page(
                            rows.stream().map(this::grantView).toList(),
                            page,
                            size,
                            count("OperationGrant", "userId", "", owners, null));
                });
    }

    public Map<String, Object> feedbacks(String status, int page, int size) {
        page(page);
        size(size);
        if (status != null
                && !Set.of("PENDING", "NEEDS_INFO", "REWARDED", "CLOSED").contains(status))
            throw new IllegalArgumentException("无效反馈状态");
        return tx.root(
                () -> {
                    var owners = ids();
                    if (owners.isEmpty()) return OperationViews.page(List.of(), page, size, 0);
                    String where =
                            " where f.userId in :owners"
                                    + (status == null ? "" : " and f.status=:status");
                    var q =
                            em.createQuery(
                                            "select f from ProductFeedback f"
                                                    + where
                                                    + " order by f.id desc",
                                            ProductFeedback.class)
                                    .setParameter("owners", owners);
                    var c =
                            em.createQuery(
                                            "select count(f) from ProductFeedback f" + where,
                                            Long.class)
                                    .setParameter("owners", owners);
                    if (status != null) {
                        q.setParameter("status", status);
                        c.setParameter("status", status);
                    }
                    var rows = q.setFirstResult(page * size).setMaxResults(size).getResultList();
                    return OperationViews.page(
                            rows.stream()
                                    .map(
                                            f ->
                                                    OperationViews.feedback(
                                                            f, username(f.getUserId()), true))
                                    .toList(),
                            page,
                            size,
                            c.getSingleResult());
                });
    }

    public Map<String, Object> activity(Long uid, int days) {
        Instant start = since(days);
        return tx.root(
                () -> {
                    var users =
                            em.createQuery("select u from User u where u.id=:u", User.class)
                                    .setParameter("u", uid)
                                    .getResultList();
                    if (users.isEmpty())
                        throw new OperationException(404, "USER_NOT_FOUND", "用户不存在");
                    var logs =
                            em.createQuery(
                                            "select l from LlmUsageLog l where l.userId=:u and"
                                                    + " l.createdAt>=:s order by l.id desc",
                                            LlmUsageLog.class)
                                    .setParameter("u", uid)
                                    .setParameter("s", start)
                                    .setMaxResults(100)
                                    .getResultList();
                    var events =
                            em.createQuery(
                                            "select e from ProductEvent e where e.userId=:u and"
                                                    + " e.createdAt>=:s order by e.id desc",
                                            ProductEvent.class)
                                    .setParameter("u", uid)
                                    .setParameter("s", start)
                                    .setMaxResults(100)
                                    .getResultList();
                    var grants =
                            em.createQuery(
                                            "select g from OperationGrant g where g.userId=:u order"
                                                    + " by g.id desc",
                                            OperationGrant.class)
                                    .setParameter("u", uid)
                                    .setMaxResults(100)
                                    .getResultList();
                    return map(
                            "user",
                            userView(users.get(0)),
                            "usage",
                            logs.stream()
                                    .map(
                                            l ->
                                                    map(
                                                            "id",
                                                            l.getId(),
                                                            "scene",
                                                            l.getScene(),
                                                            "model",
                                                            l.getModel(),
                                                            "keyMode",
                                                            l.getKeyMode(),
                                                            "promptTokens",
                                                            l.getPromptTokens(),
                                                            "completionTokens",
                                                            l.getCompletionTokens(),
                                                            "totalTokens",
                                                            l.getTotalTokens(),
                                                            "chargedTokens",
                                                            l.getChargedTokens(),
                                                            "createdAt",
                                                            l.getCreatedAt()))
                                    .toList(),
                            "events",
                            events.stream()
                                    .map(
                                            e ->
                                                    map(
                                                            "id",
                                                            e.getId(),
                                                            "name",
                                                            e.getName(),
                                                            "page",
                                                            e.getPage(),
                                                            "sessionId",
                                                            e.getSessionId(),
                                                            "durationMs",
                                                            e.getDurationMs(),
                                                            "createdAt",
                                                            e.getCreatedAt()))
                                    .toList(),
                            "grants",
                            grants.stream().map(this::grantView).toList());
                });
    }

    public Map<String, Object> analytics(int days) {
        Instant start = since(days);
        return tx.root(
                () -> {
                    List<Long> owners = ids();
                    if (owners.isEmpty())
                        return map(
                                "days",
                                days,
                                "funnel",
                                List.of(),
                                "models",
                                List.of(),
                                "errors",
                                List.of(),
                                "retention",
                                map(
                                        "day1",
                                        map("eligible", 0, "returned", 0, "rate", null),
                                        "day7",
                                        map("eligible", 0, "returned", 0, "rate", null)),
                                "daily",
                                List.of(),
                                "sources",
                                List.of(),
                                "cost",
                                map(
                                        "estimated",
                                        true,
                                        "platformEstimatedCostCny",
                                        0,
                                        "byokTokens",
                                        0,
                                        "unattributedCalls",
                                        0,
                                        "sessionMedianCny",
                                        null,
                                        "sessionP90Cny",
                                        null));
                    var errRows =
                            em.createQuery(
                                            "select e.resultCode,count(e) from ProductEvent e where"
                                                    + " e.userId in :owners and e.createdAt>=:s and"
                                                    + " e.serverEvent=true and e.name in"
                                                    + " ('MODEL_FAILED','QUOTA_BLOCKED') group by"
                                                    + " e.resultCode",
                                            Object[].class)
                                    .setParameter("owners", owners)
                                    .setParameter("s", start)
                                    .getResultList();
                    var sourceRows =
                            em.createQuery(
                                            "select e.source,count(distinct e.userId) from"
                                                + " ProductEvent e where e.userId in :owners and"
                                                + " e.createdAt>=:s and e.source is not null"
                                                + " and e.name in ('PAGE_VIEW','PRACTICE_CLICK') group"
                                                + " by e.source",
                                            Object[].class)
                                    .setParameter("owners", owners)
                                    .setParameter("s", start)
                                    .getResultList();
                    var allCore =
                            em.createQuery(
                                            "select e.userId,e.createdAt from ProductEvent e where"
                                                    + " e.userId in :owners and e.createdAt>=:s and"
                                                    + " e.serverEvent=true and e.name in :core",
                                            Object[].class)
                                    .setParameter("owners", owners)
                                    .setParameter("s", start)
                                    .setParameter("core", CORE)
                                    .getResultList();
                    var usageRows =
                            em.createQuery(
                                            "select l.createdAt,l.chargedTokens,l.platformCostMicros from LlmUsageLog l"
                                                + " where l.userId in :owners and l.createdAt>=:s",
                                            Object[].class)
                                    .setParameter("owners", owners)
                                    .setParameter("s", start)
                                    .getResultList();
                    Map<LocalDate, Set<Long>> active = new TreeMap<>();
                    Map<LocalDate, Long> charged = new TreeMap<>();
                    Map<LocalDate, Long> dailyCost = new TreeMap<>();
                    for (Object[] row : allCore)
                        active.computeIfAbsent(date((Instant) row[1]), x -> new HashSet<>())
                                .add((Long) row[0]);
                    for (Object[] row : usageRows) {
                        charged.merge(date((Instant) row[0]), (Long) row[1], Long::sum);
                        if (row[2] != null) dailyCost.merge(date((Instant) row[0]), (Long) row[2], Long::sum);
                    }
                    Set<LocalDate> dates = new TreeSet<>(active.keySet());
                    dates.addAll(charged.keySet());
                    Long byok =
                            em.createQuery(
                                            "select sum(l.totalTokens) from LlmUsageLog l where"
                                                    + " l.userId in :owners and l.createdAt>=:s and"
                                                    + " l.keyMode=:mode",
                                            Long.class)
                                    .setParameter("owners", owners)
                                    .setParameter("s", start)
                                    .setParameter("mode", KeyMode.BYOK)
                                    .getSingleResult();
                    long unattributed =
                            em.createQuery(
                                            "select count(l) from LlmUsageLog l where l.userId in"
                                                + " :owners and l.createdAt>=:s and l.sessionId is"
                                                + " null",
                                            Long.class)
                                    .setParameter("owners", owners)
                                    .setParameter("s", start)
                                    .getSingleResult();
                    var costs =
                            em
                                    .createQuery(
                                            "select sum(l.platformCostMicros) from LlmUsageLog l"
                                                + " where l.userId in :owners and l.sessionId is not null and"
                                                + " l.platformCostMicros is not null and exists"
                                                + " (select r.id from CriticReport r where r.user.id=l.userId"
                                                + " and r.session.id=l.sessionId and r.createdAt>=:s) group by"
                                                + " l.userId,l.sessionId",
                                            Long.class)
                                    .setParameter("owners", owners)
                                    .setParameter("s", start)
                                    .getResultList()
                                    .stream()
                                    .sorted()
                                    .toList();
                    return map(
                            "days",
                            days,
                            "funnel",
                            funnel(start),
                            "models",
                            usageGroups("model", owners, start),
                            "scenes",
                            usageGroups("scene", owners, start),
                            "errors",
                            errRows.stream().map(r -> map("code", r[0], "count", r[1])).toList(),
                            "sources",
                            sourceRows.stream()
                                    .map(r -> map("source", r[0], "count", r[1]))
                                    .toList(),
                            "feedbackFunnel",
                            feedbackFunnel(owners, start),
                            "retention",
                            map(
                                    "day1",
                                    retention(1, start, owners, allCore),
                                    "day7",
                                    retention(7, start, owners, allCore)),
                            "daily",
                            dates.stream()
                                    .map(
                                            d ->
                                                    map(
                                                            "date",
                                                            d,
                                                            "activeUsers",
                                                            active.getOrDefault(d, Set.of()).size(),
                                                            "chargedPoints",
                                                            points(charged.getOrDefault(d, 0L)),
                                                            "platformEstimatedCostCny",
                                                            dailyCost.containsKey(d) ? cost(dailyCost.get(d)) : null))
                                    .toList(),
                            "cost",
                            map(
                                    "estimated",
                                    true,
                                    "platformEstimatedCostCny",
                                    cost(sum("platformCostMicros", owners, start)),
                                    "byokTokens",
                                    byok == null ? 0 : byok,
                                    "unattributedCalls",
                                    unattributed,
                                    "unknownPlatformCostCalls",
                                    unknownCost(owners, start),
                                    "sessionMedianCny",
                                    median(costs),
                                    "sessionCount",
                                    costs.size(),
                                    "sessionP90Cny",
                                    costs.isEmpty()
                                            ? null
                                            : cost(
                                                    costs.get(
                                                            (int) Math.ceil(costs.size() * 0.9)
                                                                    - 1))));
                });
    }

    private Map<String, Object> budget() {
        LocalDate day = LocalDate.now(ZoneId.of("Asia/Shanghai"));
        var rows =
                em.createQuery(
                                "select b from PlatformBudgetDay b where b.day=:day",
                                com.bupt.mockinterview.billing.PlatformBudgetDay.class)
                        .setParameter("day", day)
                        .getResultList();
        return map(
                "day",
                day,
                "spentCny",
                cost(rows.isEmpty() ? 0 : rows.get(0).getSpentMicros()),
                "reservedCny",
                cost(rows.isEmpty() ? 0 : rows.get(0).getReservedMicros()));
    }

    /** Internal dimensions only; callers cannot supply query fragments. Unknown cost stays explicit. */
    private List<Map<String, Object>> usageGroups(String dimension, List<Long> owners, Instant start) {
        if (!Set.of("model", "scene").contains(dimension)) throw new IllegalArgumentException("Invalid dimension");
        var rows = em.createQuery("select l." + dimension + ",count(l),sum(l.totalTokens),"
                        + "sum(l.platformCostMicros),"
                        + "sum(case when l.status in ('FAILED','PARTIAL') then 1 else 0 end),"
                        + "sum(case when l.attemptCount>1 then l.attemptCount-1 else 0 end),"
                        + "sum(case when l.keyMode=:platform and l.platformCostMicros is null then 1 else 0 end)"
                        + " from LlmUsageLog l where l.userId in :owners and l.createdAt>=:s group by l." + dimension,
                        Object[].class)
                .setParameter("owners", owners).setParameter("s", start).setParameter("platform", KeyMode.PLATFORM)
                .getResultList();
        return rows.stream().map(r -> map(dimension, r[0], "calls", r[1], "tokens", r[2],
                "platformEstimatedCostCny", r[3] == null ? null : cost((Long) r[3]),
                "failedCalls", r[4], "retryAttempts", r[5], "unknownPlatformCostCalls", r[6])).toList();
    }

    private BigDecimal median(List<Long> costs) {
        if (costs.isEmpty()) return null;
        int n = costs.size();
        return n % 2 == 1
                ? cost(costs.get(n / 2))
                : cost(costs.get(n / 2 - 1))
                        .add(cost(costs.get(n / 2)))
                        .divide(BigDecimal.valueOf(2));
    }

    private long unknownCost(List<Long> owners, Instant start) {
        return em.createQuery(
                        "select count(l) from LlmUsageLog l where l.userId in :owners and"
                                + " l.createdAt>=:s and l.keyMode=:mode and l.platformCostMicros is"
                                + " null",
                        Long.class)
                .setParameter("owners", owners)
                .setParameter("s", start)
                .setParameter("mode", KeyMode.PLATFORM)
                .getSingleResult();
    }

    private List<Long> stage(String hql, List<Long> owners, Instant since) {
        if (owners.isEmpty()) return List.of();
        return em.createQuery(hql, Long.class)
                .setParameter("owners", owners)
                .setParameter("s", since)
                .getResultList();
    }

    private List<Map<String, Object>> funnel(Instant since) {
        List<Long> registered =
                em.createQuery("select u.id from User u where u.createdAt>=:s", Long.class)
                        .setParameter("s", since)
                        .getResultList();
        List<Long> started =
                stage(
                        "select distinct s.user.id from InterviewSession s where s.user.id in"
                                + " :owners and s.createdAt>=:s",
                        registered,
                        since);
        List<Long> answered =
                stage(
                        "select distinct t.user.id from InterviewTurn t where t.user.id in :owners"
                                + " and t.session.createdAt>=:s and t.answer is not null and"
                                + " t.answer<>''",
                        started,
                        since);
        List<Long> reported =
                stage(
                        "select distinct r.user.id from CriticReport r where r.user.id in :owners"
                                + " and r.createdAt>=:s",
                        answered,
                        since);
        List<Long> viewed =
                stage(
                        "select distinct e.userId from ProductEvent e where e.userId in :owners and"
                                + " e.createdAt>=:s and e.name='REPORT_VIEW'",
                        reported,
                        since);
        return List.of(
                map("name", "注册", "count", registered.size()),
                map("name", "开始面试", "count", started.size()),
                map("name", "保存回答", "count", answered.size()),
                map("name", "报告生成", "count", reported.size()),
                map("name", "报告查看", "count", viewed.size()));
    }

    private List<Map<String, Object>> feedbackFunnel(List<Long> owners, Instant start) {
        return List.of(
                map("name", "邀请展示", "count", countEvents("FEEDBACK_INVITE_VIEW", owners, start)),
                map("name", "打开反馈", "count", countEvents("FEEDBACK_OPEN", owners, start)),
                map(
                        "name",
                        "提交反馈",
                        "count",
                        count(
                                "ProductFeedback",
                                "userId",
                                " and x.createdAt>=:since",
                                owners,
                                start)),
                map(
                        "name",
                        "获得奖励",
                        "count",
                        count(
                                "OperationGrant",
                                "userId",
                                " and x.type='FEEDBACK' and x.createdAt>=:since",
                                owners,
                                start)));
    }

    private Map<String, Object> overviewWithin(int days, Instant start, List<Long> owners) {
        if (owners.isEmpty()) return Map.of();
        return map(
                "registeredUsers",
                em.createQuery("select count(u) from User u where u.createdAt>=:s", Long.class)
                        .setParameter("s", start)
                        .getSingleResult(),
                "interviewsStarted",
                count("InterviewSession", "user.id", " and x.createdAt>=:since", owners, start),
                "reportsGenerated",
                count("CriticReport", "user.id", " and x.createdAt>=:since", owners, start),
                "reportsViewed",
                countEvents("REPORT_VIEW", owners, start));
    }

    private long countEvents(String name, List<Long> owners, Instant start) {
        return em.createQuery(
                        "select count(distinct e.userId) from ProductEvent e where e.userId in"
                                + " :owners and e.name=:name and e.createdAt>=:s",
                        Long.class)
                .setParameter("owners", owners)
                .setParameter("name", name)
                .setParameter("s", start)
                .getSingleResult();
    }

    private LocalDate date(Instant instant) {
        return instant.atZone(ZoneId.of("Asia/Shanghai")).toLocalDate();
    }

    private Map<String, Object> retention(
            int offset, Instant start, List<Long> owners, List<Object[]> core) {
        var accounts =
                em.createQuery(
                                "select u.id,u.createdAt from User u where u.createdAt>=:s",
                                Object[].class)
                        .setParameter("s", start)
                        .getResultList();
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Shanghai"));
        long eligible = 0, returned = 0;
        for (Object[] u : accounts) {
            LocalDate day = date((Instant) u[1]).plusDays(offset);
            if (!day.isBefore(today)) continue;
            eligible++;
            if (core.stream().anyMatch(e -> u[0].equals(e[0]) && date((Instant) e[1]).equals(day)))
                returned++;
        }
        return map(
                "eligible",
                eligible,
                "returned",
                returned,
                "rate",
                eligible == 0 ? null : (double) returned / eligible);
    }
}
