package com.agenticai.core.tenancy;

import com.agenticai.core.security.edition.ConditionalOnBillingEdition;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.agenticai.core.billing.email.BillingEmailService;
import com.agenticai.core.security.TenantPlan;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Academy Fast-Track Scholarship one-click fulfilment - the auto-reverter for
 * complimentary plan grants (CORE-COMP-GRANT-CONTRACT.md: "On expiry the plan
 * should revert"). Ticks hourly and drops any tenant whose {@code
 * comp_plan_expires_at} has passed back to {@code FREE}, clearing the comp-grant
 * bookkeeping (see {@link Tenant#revertComplimentaryGrant}).
 *
 * <p>Unlike {@code WorkflowScheduler} (which iterates per-tenant schemas), this
 * job queries the single {@code control_plane.tenant} table directly - a comp
 * grant lives on that shared control-plane row, not in any per-tenant schema, so
 * one plain repository query covers every tenant. Not {@code @Profile}-gated, so
 * it runs whenever the app runs; it is a no-op on any deployment that has never
 * issued a comp grant (the {@code expires_at < now} filter excludes the NULL
 * majority).
 *
 * <p>Multi-replica: each tick runs under {@link PgAdvisoryJobLock} as
 * belt-and-braces, not for correctness - the revert itself was and remains
 * idempotent (a second revert lands an already-FREE tenant on FREE again),
 * so the lock only spares concurrent replicas from redundant scans and from
 * two sessions flushing the same tenant rows in the same instant.
 *
 * <p>An hourly tick means a grant can outlive its exact expiry by up to ~an hour
 * - an accepted trade-off for a background reverter, identical in spirit to
 * {@code WorkflowScheduler}'s stated one-minute slack, and generous for a
 * months-long comp grant.
 */
@Component
@ConditionalOnBillingEdition
public class CompGrantExpiryReverter {

    private static final Logger log = LoggerFactory.getLogger(CompGrantExpiryReverter.class);
    private static final long TICK_MS = 3_600_000; // hourly

    private static final String JOB_LOCK_NAME = "comp-grant-expiry-revert";

    private final TenantRepository tenantRepository;
    private final PgAdvisoryJobLock jobLock;

    /**
     * Optional (field-injected so the two-argument constructor tests rely on is unchanged): when present, a tenant
     * that lands on FREE gets the "your workspace is now on Free - here is how to get the full plan back" mail,
     * AFTER the revert has committed. Absent (plain unit tests) = no mail.
     */
    @Autowired(required = false)
    private BillingEmailService billingEmailService;

    private record Ended(UUID id, String name) {
    }

    public CompGrantExpiryReverter(TenantRepository tenantRepository, PgAdvisoryJobLock jobLock) {
        this.tenantRepository = tenantRepository;
        this.jobLock = jobLock;
    }

    // @Transactional stays on the OUTER scheduled method (moving it to the
    // wrapped body would hit Spring's self-invocation limitation and go
    // inert - see DailyQuotaResetScheduler's history of exactly that bug).
    // Inside the transaction, the lock's ConnectionCallback runs on the
    // transaction-bound connection; advisory locks are session-scoped, not
    // transaction-scoped, and are explicitly released before commit/rollback,
    // so the pairing is safe.
    @Scheduled(fixedRate = TICK_MS)
    @Transactional
    public void revertExpiredCompGrants() {
        // Lock-not-acquired is only DEBUG-logged (by the lock service itself) -
        // another replica reverting is success, not an incident.
        jobLock.tryRunExclusive(JOB_LOCK_NAME, this::revertExpiredCompGrantsExclusively);
    }

    private void revertExpiredCompGrantsExclusively() {
        List<Tenant> expired = tenantRepository.findByCompPlanExpiresAtBefore(Instant.now());
        if (expired.isEmpty()) {
            return;
        }
        List<Ended> landedOnFree = new ArrayList<>();
        for (Tenant tenant : expired) {
            log.info("Reverting expired comp grant to FREE: tenant={} expiredAt={}",
                tenant.getId(), tenant.getCompPlanExpiresAt());
            tenant.revertComplimentaryGrant();
            if (tenant.getPlan() == TenantPlan.FREE) {
                landedOnFree.add(new Ended(tenant.getId(), tenant.getName()));
            }
        }
        tenantRepository.saveAll(expired);
        notifyEnded(landedOnFree);
    }

    /** Mails only after the revert commits (never while the DB transaction is open, never for a rolled-back one). */
    private void notifyEnded(List<Ended> ended) {
        if (ended.isEmpty() || billingEmailService == null) {
            return;
        }
        Runnable send = () -> {
            for (Ended e : ended) {
                try {
                    billingEmailService.sendCompGrantEndedEmail(e.id(), e.name());
                } catch (RuntimeException ex) {
                    log.warn("Comp-grant-ended email failed for tenant {}: {}", e.id(), ex.getMessage());
                }
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    send.run();
                }
            });
        } else {
            send.run();
        }
    }
}
