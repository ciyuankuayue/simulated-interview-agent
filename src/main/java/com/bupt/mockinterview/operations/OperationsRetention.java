package com.bupt.mockinterview.operations;

import jakarta.persistence.*;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.*;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Dedicated maintenance whitelist: enumerate accounts, then perform owner-scoped cleanup in each
 * tenant.
 */
@Configuration
@EnableScheduling
public class OperationsRetention {
    @PersistenceContext private EntityManager em;
    private final OperationsTransactions tx;

    public OperationsRetention(OperationsTransactions tx) {
        this.tx = tx;
    }

    @Scheduled(cron = "0 15 3 * * *", zone = "Asia/Shanghai")
    public void purge() {
        List<Long> ids =
                tx.root(
                        () ->
                                em.createQuery("select u.id from User u", Long.class)
                                        .getResultList());
        for (Long uid : ids)
            tx.tenant(
                    uid,
                    () -> {
                        em.createQuery(
                                        "delete from ProductEvent e where e.userId=:u and"
                                                + " e.createdAt<:before")
                                .setParameter("u", uid)
                                .setParameter("before", Instant.now().minus(90, ChronoUnit.DAYS))
                                .executeUpdate();
                        em.createQuery(
                                        "update ProductFeedback f set"
                                            + " f.contact=null,f.followUp=false,f.contactUpdatedAt=null"
                                            + " where f.userId=:u and f.contactUpdatedAt<:before")
                                .setParameter("u", uid)
                                .setParameter("before", Instant.now().minus(90, ChronoUnit.DAYS))
                                .executeUpdate();
                        em.createQuery(
                                        "update ProductFeedback f set"
                                            + " f.experience=:redacted,f.originalExperience=null,f.suggestions=null,f.reviewNote=null"
                                            + " where f.userId=:u and f.updatedAt<:before")
                                .setParameter("u", uid)
                                .setParameter("before", Instant.now().minus(180, ChronoUnit.DAYS))
                                .setParameter("redacted", "[已按保留期限清理]")
                                .executeUpdate();
                        return null;
                    });
    }
}
