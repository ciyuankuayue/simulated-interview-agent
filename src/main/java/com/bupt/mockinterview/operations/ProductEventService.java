package com.bupt.mockinterview.operations;

import static com.bupt.mockinterview.operations.OperationDtos.*;

import jakarta.persistence.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class ProductEventService {
    private static final Logger log = LoggerFactory.getLogger(ProductEventService.class);
    static final Set<String> SERVER =
            Set.of(
                    "REGISTERED",
                    "LOGIN",
                    "IMPORT_SUCCEEDED",
                    "INTERVIEW_STARTED",
                    "ANSWER_SAVED",
                    "REPORT_GENERATED",
                    "MODEL_FAILED",
                    "QUOTA_BLOCKED",
                    "SETTINGS_SAVED",
                    "BUDDY_SUCCEEDED");
    static final Set<String> CLIENT =
            Set.of(
                    "PAGE_VIEW",
                    "PRACTICE_CLICK",
                    "API_CONFIG_OPEN",
                    "REPORT_VIEW",
                    "FEEDBACK_INVITE_VIEW",
                    "FEEDBACK_OPEN");
    static final Set<String> PAGES =
            Set.of(
                    "prepare",
                    "interview",
                    "history",
                    "weak",
                    "materials",
                    "companion",
                    "coach",
                    "settings",
                    "feedback");
    @PersistenceContext private EntityManager em;
    private final OperationsTransactions tx;

    public ProductEventService(OperationsTransactions tx) {
        this.tx = tx;
    }

    public void recordServer(
            Long uid, String name, String eventId, Long sessionId, String page, String code) {
        if (uid == null
                || !SERVER.contains(name)
                || eventId == null
                || !eventId.matches("[A-Za-z0-9:_-]{1,120}")
                || (page != null && !PAGES.contains(page))
                || (code != null && !code.matches("[A-Za-z0-9_.:-]{1,64}"))) return;
        try {
            tx.tenant(
                    uid,
                    () -> {
                        lock(uid);
                        append(uid, name, eventId, sessionId, page, null, null, null, code, true);
                        return null;
                    });
        } catch (RuntimeException ex) {
            log.warn(
                    "Product event skipped name={} reason={}", name, ex.getClass().getSimpleName());
        }
    }

    public int batch(Long uid, EventsRequest req) {
        for (EventInput e : req.events()) {
            if (!CLIENT.contains(e.name())
                    || (e.page() != null && !PAGES.contains(e.page()))
                    || (e.durationMs() != null && (e.durationMs() < 0 || e.durationMs() > 3600000))
                    || (e.source() != null && !e.source().matches("[A-Za-z0-9_-]{1,32}"))
                    || (e.device() != null
                            && !Set.of("DESKTOP", "MOBILE", "UNKNOWN").contains(e.device())))
                throw new IllegalArgumentException("不允许的事件字段");
        }
        return tx.tenant(
                uid,
                () -> {
                    lock(uid);
                    var preference =
                            em.createQuery(
                                            "select p from AnalyticsPreference p where p.userId=:u",
                                            AnalyticsPreference.class)
                                    .setParameter("u", uid)
                                    .getResultList();
                    if (!preference.isEmpty() && !preference.get(0).isEnabled()) return 0;
                    long recent =
                            em.createQuery(
                                            "select count(e) from ProductEvent e where e.userId=:u"
                                                    + " and e.serverEvent=false and"
                                                    + " e.createdAt>=:since",
                                            Long.class)
                                    .setParameter("u", uid)
                                    .setParameter("since", java.time.Instant.now().minusSeconds(60))
                                    .getSingleResult();
                    if (recent + req.events().size() > 120)
                        throw new OperationException(429, "EVENT_RATE_LIMIT", "操作过于频繁，请稍后再试");
                    int accepted = 0;
                    for (EventInput e : req.events()) {
                        if (e.sessionId() != null) {
                            long count =
                                    em.createQuery(
                                                    "select count(s) from InterviewSession s where"
                                                            + " s.user.id=:u and s.id=:s",
                                                    Long.class)
                                            .setParameter("u", uid)
                                            .setParameter("s", e.sessionId())
                                            .getSingleResult();
                            if (count == 0) throw new IllegalArgumentException("场次不存在");
                        }
                        if (append(
                                uid,
                                e.name(),
                                e.eventId(),
                                e.sessionId(),
                                e.page(),
                                e.durationMs(),
                                e.source(),
                                e.device(),
                                null,
                                false)) accepted++;
                    }
                    return accepted;
                });
    }

    private void lock(Long uid) {
        em.createQuery("select u from User u where u.id=:u")
                .setParameter("u", uid)
                .setLockMode(LockModeType.PESSIMISTIC_WRITE)
                .getSingleResult();
    }

    private boolean append(
            Long uid,
            String name,
            String eventId,
            Long sessionId,
            String page,
            Long duration,
            String source,
            String device,
            String code,
            boolean server) {
        long exists =
                em.createQuery(
                                "select count(e) from ProductEvent e where e.userId=:u and"
                                        + " e.eventId=:id",
                                Long.class)
                        .setParameter("u", uid)
                        .setParameter("id", eventId)
                        .getSingleResult();
        if (exists > 0) return false;
        ProductEvent e = new ProductEvent();
        e.setUserId(uid);
        e.setName(name);
        e.setEventId(eventId);
        e.setSessionId(sessionId);
        e.setPage(page);
        e.setDurationMs(duration);
        e.setSource(source);
        e.setDevice(device);
        e.setResultCode(code);
        e.setServerEvent(server);
        em.persist(e);
        return true;
    }
}
