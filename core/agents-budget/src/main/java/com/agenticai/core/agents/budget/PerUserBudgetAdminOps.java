package com.agenticai.core.agents.budget;

import com.agenticai.core.agents.budget.PerUserBudgetService.BudgetConfig;
import com.agenticai.core.agents.budget.PerUserBudgetService.BudgetStatus;
import com.agenticai.core.agents.budget.PerUserBudgetService.Modality;
import com.agenticai.core.security.TenantPlan;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.metadata.Usage;

/**
 * The read/status/top-up/admin-config operations of {@link PerUserBudgetService}, extracted so the
 * service stays under the file-size cap. Package-private; callers still go through {@link
 * PerUserBudgetService}.
 */
final class PerUserBudgetAdminOps {

    private static final Logger log = LoggerFactory.getLogger(PerUserBudgetAdminOps.class);

    private final PerUserBudgetService owner;

    PerUserBudgetAdminOps(PerUserBudgetService owner) {
        this.owner = owner;
    }

    /**
     * Current effective status of a (tenant, user, modality) window - the effective cap (base +
     * live top-up credit), spend so far, remaining headroom (never negative), and seconds to reset.
     * Used by the top-up confirm response so a client can immediately show the restored budget.
     */
    BudgetStatus status(String tenantId, String userKey, Modality modality) {
        BudgetConfig config = owner.configResolver.resolveConfig(tenantId, userKey);
        long cap = owner.effectiveCapMicroCents(config, modality, tenantId, userKey);
        long spent =
                owner.counters.currentSpendMicroCents(
                        tenantId, userKey, modality, config.windowSeconds());
        long remaining = Math.max(0L, cap - spent);
        return new BudgetStatus(
                modality.name().toLowerCase(java.util.Locale.ROOT),
                PerUserBudgetCounters.microCentsToUsd(cap),
                PerUserBudgetCounters.microCentsToUsd(spent),
                PerUserBudgetCounters.microCentsToUsd(remaining),
                owner.counters.remainingWindowSeconds(
                        PerUserBudgetCounters.key(tenantId, userKey, modality)));
    }

    BigDecimal costForUsage(
            String tenantId,
            String userKey,
            Modality modality,
            String model,
            Usage usage,
            TenantPlan knownPlan) {
        return PerUserBudgetUsageCost.costForUsage(
                owner.pricingCatalog,
                owner.configResolver,
                tenantId,
                userKey,
                model,
                usage,
                knownPlan);
    }

    /**
     * The tail of every reconcile path: converts {@code actualCost} to micro-cents and applies the
     * signed delta against {@code reservedMicroCents} to this window's counter.
     */
    void applySpendDelta(
            String tenantId,
            String userKey,
            Modality modality,
            long reservedMicroCents,
            BigDecimal actualCost,
            TenantPlan knownPlan) {
        applySpendDelta(
                tenantId, userKey, modality, reservedMicroCents, actualCost, knownPlan, false);
    }

    /**
     * @param keepReservation true when {@code actualCost} is only an estimate of what a response
     *     cost: the reservation then stays as a floor and the charge can only grow, never shrink
     *     below what was reserved.
     */
    void applySpendDelta(
            String tenantId,
            String userKey,
            Modality modality,
            long reservedMicroCents,
            BigDecimal actualCost,
            TenantPlan knownPlan,
            boolean keepReservation) {
        long actualMicroCents =
                actualCost == null || actualCost.signum() <= 0
                        ? 0L
                        : actualCost
                                .multiply(PerUserBudgetService.MICRO_CENTS_PER_USD)
                                .setScale(0, RoundingMode.HALF_UP)
                                .longValueExact();
        long delta = actualMicroCents - reservedMicroCents;
        if (keepReservation && delta < 0) {
            return;
        }
        if (delta == 0) {
            return;
        }
        BudgetConfig config = owner.configResolver.resolveConfig(tenantId, userKey, knownPlan);
        owner.counters.incrementBy(
                PerUserBudgetCounters.key(tenantId, userKey, modality),
                Duration.ofSeconds(config.windowSeconds()),
                delta);
    }

    /**
     * A response that was produced and shown but came back without usage metadata (a provider that
     * omits it, a stream cancelled before the usage event). Releasing the reservation here, as a
     * zero-token call would, made the call free: a user could cancel every stream after the text
     * was out and spend nothing. The reservation is kept as the charge, raised to the size of what
     * was actually received if that is more; with no reservation (a sibling call of the same turn)
     * the received text is charged at the fallback rate.
     */
    void chargeUnmeasuredResponse(
            String tenantId,
            String userKey,
            Modality modality,
            long reservedMicroCents,
            long receivedChars,
            TenantPlan knownPlan) {
        if (tenantId == null || receivedChars <= 0) {
            return;
        }
        BigDecimal estimated = estimateCostForChars(tenantId, userKey, receivedChars, knownPlan);
        if (reservedMicroCents > 0) {
            applySpendDelta(
                    tenantId, userKey, modality, reservedMicroCents, estimated, knownPlan, true);
        } else {
            owner.recordSpend(tenantId, userKey, modality, estimated, knownPlan);
        }
    }

    /** The output-token estimate for {@code receivedChars} of text, priced at the fallback rate. */
    BigDecimal estimateCostForChars(
            String tenantId, String userKey, long receivedChars, TenantPlan knownPlan) {
        long tokens = (long) Math.ceil(receivedChars / PerUserBudgetService.CHARS_PER_TOKEN);
        return owner.configResolver
                .resolveConfig(tenantId, userKey, knownPlan)
                .fallbackUsdPer1kTokens()
                .multiply(BigDecimal.valueOf(tokens))
                .divide(BigDecimal.valueOf(1000), 8, RoundingMode.HALF_UP);
    }

    /**
     * Full-release convenience for {@link #reconcileSpendReservationForCall} when a turn ends with
     * NO usage at all to report (the whole provider-chain attempt failed before any model produced
     * usage metadata) - equivalent to calling it with null tokens, spelled out separately so {@code
     * FreeTierChatClientService}'s failure-path call sites don't need to fabricate a model name for
     * a call that never happened.
     */
    void releaseSpendReservation(
            String tenantId,
            String userKey,
            Modality modality,
            long reservedMicroCents,
            TenantPlan knownPlan) {
        owner.reconcileSpendReservationForCall(
                tenantId, userKey, modality, reservedMicroCents, null, null, null, knownPlan);
    }

    // ---- P2 paid top-up / overage -----------------------------------------

    /**
     * PLATFORM-ADMIN-GOVERNANCE-AND-CONTROLS.md §4 P2 - credits {@code usdAmount} of PAID budget to
     * the CURRENT window for this (tenant, user, modality). Adds to a separate credit counter that
     * {@link #checkBudget} folds into the effective cap, so the next turn passes the gate without a
     * plan change; the credit expires with the window (aligned to the spend counter's remaining TTL
     * when one exists, otherwise the configured window length) so unused top-up never carries
     * silently into the next day.
     *
     * <p>Called only from the payment-confirm path (a successful, signature- verified top-up
     * payment), never from the chat hot path. Unlike the metering reads/writes elsewhere in this
     * class, this deliberately does <b>not</b> fail open: by the time it runs the customer has
     * already been charged, so a Redis failure here must surface to the caller (which can then
     * reconcile / retry) rather than silently swallow paid credit. A null/non-positive amount, or
     * one that rounds below one micro-cent, is a no-op.
     *
     * <p>If a failure ever does land between the {@code INCRBY} below and its {@code EXPIRE} (e.g.
     * this JVM dies mid-call, after the caller already saw success from a prior partial retry),
     * {@link PerUserBudgetCounters#currentCreditMicroCents} self-heals it on its next read the same
     * way it does for the spend counter - see {@code PerUserBudgetTtlRepair#repairMissingTtl}'s
     * javadoc.
     */
    void creditTopUp(String tenantId, String userKey, Modality modality, BigDecimal usdAmount) {
        if (tenantId == null || modality == null || usdAmount == null || usdAmount.signum() <= 0) {
            return;
        }
        long microCents =
                usdAmount
                        .multiply(PerUserBudgetService.MICRO_CENTS_PER_USD)
                        .setScale(0, RoundingMode.HALF_UP)
                        .longValueExact();
        if (microCents <= 0) {
            return;
        }
        String creditKey = PerUserBudgetCounters.creditKey(tenantId, userKey, modality);
        owner.redisTemplate.opsForValue().increment(creditKey, microCents);
        // Align the credit's expiry to the spend window in progress so top-up
        // and spend reset together; if no spend counter exists yet (nothing
        // spent this window), fall back to a fresh full window.
        Long spendTtl =
                owner.counters.remainingWindowSeconds(
                        PerUserBudgetCounters.key(tenantId, userKey, modality));
        long ttlSeconds =
                (spendTtl != null && spendTtl > 0)
                        ? spendTtl
                        : owner.configResolver.resolveConfig(tenantId, userKey).windowSeconds();
        owner.redisTemplate.expire(creditKey, Duration.ofSeconds(ttlSeconds));
        log.info(
                "Credited ${} per-user {} top-up for tenant '{}'",
                usdAmount.toPlainString(),
                modality.name().toLowerCase(java.util.Locale.ROOT),
                tenantId);
    }

    // ---- config read/write (admin surface) -------------------------------

    /** Current effective global config - what the admin GET endpoint returns. */
    BudgetConfig currentGlobalConfig() {
        return owner.configResolver.loadGlobalConfig();
    }

    /**
     * Admin write path - upserts each provided (non-null) field as its {@code platform_setting}
     * row, then busts the snapshot cache so the change is live on the next turn without a redeploy.
     * Nulls leave that field's row untouched (partial update), mirroring {@code
     * PlatformLlmTaskService#upsert}'s optional-field posture.
     */
    BudgetConfig updateGlobalConfig(
            Boolean enabled,
            BigDecimal dailyUsd,
            Integer windowSeconds,
            Map<Modality, Integer> splitPct,
            BigDecimal fallbackUsdPer1kTokens,
            String updatedBy) {
        return owner.configResolver.updateGlobalConfig(
                enabled, dailyUsd, windowSeconds, splitPct, fallbackUsdPer1kTokens, updatedBy);
    }

    /**
     * Proactive keyspace sweep, called once a day by {@code DailyQuotaResetScheduler}'s idempotent
     * run - same purpose as {@link FreeTierUsageLimiterService#repairAllMissingTtls}: unlike the
     * lazy repair inside {@link PerUserBudgetCounters#currentSpendMicroCents}/{@link
     * PerUserBudgetCounters#currentCreditMicroCents} (which only fixes a counter the next time
     * something happens to read that exact key), this walks every existing spend and paid-credit
     * counter, across every modality and tenant/user, and repairs any that lost its TTL. Uses
     * {@code SCAN} (cursor-based, incremental) rather than {@code KEYS} - verify-pass fix: {@code
     * KEYS} is O(N) over the ENTIRE keyspace of the Redis DB (not just matching keys) and blocks
     * Redis's single-threaded event loop for its full duration, stalling every other tenant's live
     * rate-limiting/session traffic on a shared instance; {@code SCAN} walks the same keyspace
     * incrementally without that blocking cost. Returns the number of owner.counters repaired.
     */
    int repairAllMissingTtls() {
        int repaired = 0;
        try {
            // Verify-pass fix: this call was unprotected - a transient
            // failure reading platform config (platformSettingsService) would
            // throw out of this method entirely, aborting the sweep before
            // either prefix was even attempted and, when called from
            // DailyQuotaResetScheduler, propagating up to the caller.
            int windowSeconds = owner.configResolver.effectiveGlobalConfig().windowSeconds();
            repaired += owner.counters.repairAllPrefixes(windowSeconds);
        } catch (RuntimeException e) {
            log.debug(
                    "Per-user budget TTL repair sweep failed to load config - leaving for the next lazy read to retry",
                    e);
        }
        return repaired;
    }
}
