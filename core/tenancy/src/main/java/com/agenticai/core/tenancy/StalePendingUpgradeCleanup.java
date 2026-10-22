package com.agenticai.core.tenancy;

import com.agenticai.core.security.edition.ConditionalOnEditionFeature;
import com.agenticai.core.security.edition.EditionFeature;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Freemium billing hardening (V471) - clears abandoned self-serve upgrades. {@code
 * Tenant.beginUpgrade} writes {@code pending_plan} / {@code pending_gateway_subscription_id} /
 * {@code pending_upgrade_started_at} the moment a gateway subscription is created, expecting {@code
 * subscription.activated}/{@code .charged} to confirm it or a terminal event to clear it. A
 * customer who simply closes the checkout page produces NEITHER - the gateway's own 48h {@code
 * expire_by} lapses it gateway-side, but app-side the pending row sat forever: the tenant stayed
 * "mid-upgrade" indefinitely, and a much-later stray webhook could resurrect an upgrade they walked
 * away from days ago.
 *
 * <p>Ticks hourly (same cadence and lock posture as {@link CompGrantExpiryReverter}) and clears any
 * pending upgrade older than {@link #STALE_AFTER_HOURS} - comfortably past Razorpay's 48h
 * authorization window ({@code RazorpayGatewayClient#AUTHORIZATION_WINDOW_HOURS}), so a customer
 * still legitimately inside checkout is never swept. The clear is {@link
 * Tenant#cancelPendingUpgrade}: plan untouched, the tenant simply stays on whatever plan it already
 * had - identical semantics to the gateway-side "checkout abandoned" terminal event.
 *
 * <p>Idempotent and multi-replica-safe for the same reasons the comp-grant reverter is: the clear
 * is a fixed landing state, and {@link PgAdvisoryJobLock} only spares replicas redundant scans.
 */
@Component
@ConditionalOnEditionFeature(EditionFeature.BILLING_PAYMENTS)
public class StalePendingUpgradeCleanup {

    private static final Logger log = LoggerFactory.getLogger(StalePendingUpgradeCleanup.class);
    private static final long TICK_MS = 3_600_000; // hourly

    // Razorpay's own subscription authorization window is 48h; 72h leaves a
    // full day of slack so a customer near the end of a real checkout window
    // is never swept mid-flow (Stripe's incomplete subscription window is
    // 23h by default - also comfortably inside this).
    private static final long STALE_AFTER_HOURS = 72;

    private static final String JOB_LOCK_NAME = "stale-pending-upgrade-cleanup";

    private final TenantRepository tenantRepository;
    private final PgAdvisoryJobLock jobLock;

    public StalePendingUpgradeCleanup(
            TenantRepository tenantRepository, PgAdvisoryJobLock jobLock) {
        this.tenantRepository = tenantRepository;
        this.jobLock = jobLock;
    }

    // See CompGrantExpiryReverter for why @Transactional stays on the outer
    // scheduled method (Spring self-invocation would make it inert inside).
    @Scheduled(fixedRate = TICK_MS)
    @Transactional
    public void clearStalePendingUpgrades() {
        jobLock.tryRunExclusive(JOB_LOCK_NAME, this::clearStalePendingUpgradesExclusively);
    }

    private void clearStalePendingUpgradesExclusively() {
        Instant cutoff = Instant.now().minus(STALE_AFTER_HOURS, ChronoUnit.HOURS);
        List<Tenant> stale =
                tenantRepository
                        .findByPendingGatewaySubscriptionIdIsNotNullAndPendingUpgradeStartedAtBefore(
                                cutoff);
        if (stale.isEmpty()) {
            return;
        }
        for (Tenant tenant : stale) {
            log.info(
                    "Clearing stale pending upgrade: tenant={} pendingPlan={} pendingSub={} startedAt={}",
                    tenant.getId(),
                    tenant.getPendingPlan(),
                    tenant.getPendingGatewaySubscriptionId(),
                    tenant.getPendingUpgradeStartedAt());
            tenant.cancelPendingUpgrade();
        }
        tenantRepository.saveAll(stale);
    }
}
