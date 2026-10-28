package com.agenticai.core.agents.budget;

import com.agenticai.core.audit.NotificationService;
import com.agenticai.core.chat.CacheTokenAccounting;
import com.agenticai.core.chat.LlmPricingCatalog;
import com.agenticai.core.chat.ReasoningTokenAccounting;
import com.agenticai.core.security.TenantLimits;
import com.agenticai.core.security.TenantPlan;
import com.agenticai.core.tenancy.PlatformSettingsService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * PLATFORM-ADMIN-GOVERNANCE-AND-CONTROLS.md §4 (Budgets &amp; Limits) Phase P1
 * - the platform's own per-USER spend ceiling on platform-hosted LLM usage,
 * denominated in DOLLARS rather than tokens (voice/image/video aren't
 * token-priced, so a dollar cap is the only unit that spans every modality).
 *
 * <p><b>Why this exists alongside {@code FreeTierUsageLimiterService}.</b> That
 * limiter caps message and token <em>counts</em> per (tenant) for the hosted
 * pool. This one caps real <em>dollar spend</em> per (tenant, user) - a much
 * finer gate that (a) attributes cost to the individual user on a shared
 * tenant rather than letting one user burn a whole tenant's room, and (b)
 * meters actual money (per-call cost from {@link LlmPricingCatalog}), so a
 * few expensive turns count for more than many cheap ones even at identical
 * token counts. Both run; whichever blocks first wins. This service is the
 * dollar gate; that one is the count gate.
 *
 * <p><b>Redis shape mirrors {@code FreeTierUsageLimiterService}</b> exactly:
 * fixed-window {@code INCRBY} + conditional {@code EXPIRE} (expiry set once,
 * on the write that created the key), and <b>fail-open on any Redis error</b>
 * (a metering outage must never break a chat turn - identical posture and
 * reasoning to that class's javadoc). The only structural difference: one
 * counter <em>per modality</em> ({@link Modality}) per (tenant, user), so
 * TEXT / IMAGE / VOICE / VIDEO are independent gates - exhausting the image
 * sub-cap never blocks a text turn, and vice versa. Values are stored in USD
 * <b>micro-cents</b> (1 USD = {@value #MICRO_CENTS_PER_USD_LITERAL}
 * micro-cents) so spend accumulates as a Redis integer without losing the
 * sub-cent precision a real per-call cost needs.
 *
 * <p><b>Runtime-configurable, no redeploy</b> (§4 "editable in admin"): the
 * budget amount, window, per-modality split, and the fallback price for
 * hosted models {@link LlmPricingCatalog} doesn't know are all read from
 * {@link PlatformSettingsService} (Postgres-backed {@code platform_setting}
 * rows, seeded by {@code V117__per_user_budget_settings.sql} but falling back
 * to the same defaults here if the row is absent). A tiny time-boxed snapshot
 * cache ({@code app.per-user-budget.config-cache-ms}, default 10s) keeps this
 * off Postgres on the chat hot path while still picking up an admin change
 * within the TTL; the admin write path ({@link #updateGlobalConfig}) busts
 * the cache immediately so a change is visible on the very next turn in the
 * same JVM.
 *
 * <p><b>Precedence</b> (§4): per-user override &rarr; per-tenant override
 * &rarr; per-plan &rarr; global default. Global default and the per-plan tier
 * (FREE baseline, PREMIUM/UNLIMITED multiplier - same convention {@link
 * FreeTierUsageLimiterService} already established) are both implemented;
 * {@link PerUserBudgetConfigResolver#resolveConfig} is the single seam where a future per-user/
 * per-tenant override slots in (see its javadoc) without touching any call
 * site.
 */
@Component
public class PerUserBudgetService {

    private static final Logger log = LoggerFactory.getLogger(PerUserBudgetService.class);

    static final long MICRO_CENTS_PER_USD_LITERAL = 100_000_000L;
    static final BigDecimal MICRO_CENTS_PER_USD = BigDecimal.valueOf(MICRO_CENTS_PER_USD_LITERAL);

    // PlatformSetting keys - also the rows V117 seeds. Kept public so the
    // admin controller/UI and any test can reference the exact same strings.
    public static final String KEY_ENABLED = "platform.budget.per-user.enabled";
    public static final String KEY_DAILY_USD = "platform.budget.per-user.daily-usd";
    public static final String KEY_WINDOW_SECONDS = "platform.budget.per-user.window-seconds";
    public static final String KEY_SPLIT_PCT_PREFIX = "platform.budget.per-user.split-pct.";
    public static final String KEY_FALLBACK_USD_PER_1K = "platform.budget.per-user.fallback-usd-per-1k-tokens";

    private final StringRedisTemplate redisTemplate;
    private final LlmPricingCatalog pricingCatalog;
    // Per-plan tier (§4 precedence: per-user -> per-tenant -> per-plan ->
    // global). Same multiplier convention FreeTierUsageLimiterService already
    // established for this exact "scale the FREE-tier default per plan"
    // problem (direct feedback 2026-07-26: "Premium... 2x, Unlimited... 3x,
    // based on the quota configured for free users") - reusing the identical
    // config-property names here (rather than inventing per-user-budget-
    // specific ones) means one admin-tunable pair of knobs governs both the
    // message/token count gate AND this dollar gate's plan scaling. The
    // multipliers, defaults and snapshot cache live in the resolver.
    private final PerUserBudgetConfigResolver configResolver;
    /** Redis counters, TTL repair and the exhaustion bell. */
    private final PerUserBudgetCounters counters;

    public PerUserBudgetService(
        StringRedisTemplate redisTemplate,
        PlatformSettingsService platformSettingsService,
        LlmPricingCatalog pricingCatalog,
        @Value("${app.per-user-budget.config-cache-ms:10000}") long configCacheMs,
        TenantLimits tenantLimits,
        @Value("${app.platform-llm.premium-multiplier:2}") int premiumMultiplier,
        @Value("${app.platform-llm.unlimited-multiplier:3}") int unlimitedMultiplier,
        ObjectProvider<NotificationService> notificationService
    ) {
        this.redisTemplate = redisTemplate;
        this.pricingCatalog = pricingCatalog;
        this.configResolver = new PerUserBudgetConfigResolver(
            platformSettingsService, configCacheMs, tenantLimits, premiumMultiplier, unlimitedMultiplier);
        this.counters = new PerUserBudgetCounters(redisTemplate, notificationService);
    }

    /** The four cost-bearing modalities, each an independent per-user sub-cap. */
    public enum Modality {
        TEXT,
        IMAGE,
        VOICE,
        VIDEO
    }

    /**
     * Pre-call gate. Throws {@link PerUserBudgetExhaustedException} (a subclass
     * of {@code TenantLimitExceededException}, mapped to a client-actionable
     * HTTP 402 "top up to continue" response in {@code GlobalExceptionHandler}
     * - P2's distinct payable signal, in place of P1's flat 403) when this
     * (tenant, user, modality) window's accumulated spend is already at or past
     * its effective cap. Call BEFORE the actual call - this reads what {@link
     * #recordSpend} has accumulated so far and rejects the NEXT call once a
     * prior one pushed the window over.
     *
     * <p>Fail-open: a Redis outage (read returns null) is treated as zero
     * spend and lets the call through - metering must never break a turn.
     */
    public void checkBudget(String tenantId, String userKey, Modality modality) {
        checkBudget(tenantId, userKey, modality, null);
    }

    /**
     * Same as {@link #checkBudget(String, String, Modality)} but reuses a
     * {@link TenantPlan} the caller already resolved for this same turn
     * (2026-08-06 dedupe fix - {@code AgentChatService#chat} resolves the
     * tenant's plan ONCE per turn and threads it through every call site that
     * used to re-query {@link TenantLimits#plan} independently) instead of
     * looking it up again here. Pass {@code null} for {@code knownPlan} to
     * fall back to the internal lookup, identical to the two-arg-less overload.
     */
    public void checkBudget(String tenantId, String userKey, Modality modality, TenantPlan knownPlan) {
        if (tenantId == null) {
            return;
        }
        BudgetConfig config = configResolver.resolveConfig(tenantId, userKey, knownPlan);
        if (!config.enabled()) {
            return;
        }
        // Effective cap = the base per-user cap PLUS any paid top-up credit
        // (P2) still live in this window. A top-up (creditTopUp) raises this
        // ceiling for the current window only; when nothing has been topped up
        // the credit read is 0 and this is exactly the P1 base-cap gate.
        long effectiveCapMicroCents = effectiveCapMicroCents(config, modality, tenantId, userKey);
        long spent = counters.currentSpendMicroCents(tenantId, userKey, modality, config.windowSeconds());
        if (spent >= effectiveCapMicroCents) {
            String spendKey = PerUserBudgetCounters.key(tenantId, userKey, modality);
            counters.notifyBudgetExhausted(spendKey, config.windowSeconds(), tenantId, userKey, modality, effectiveCapMicroCents);
            throw new PerUserBudgetExhaustedException(
                counters.budgetMessage(config, modality, effectiveCapMicroCents, spendKey),
                modality.name().toLowerCase(java.util.Locale.ROOT),
                PerUserBudgetCounters.microCentsToUsd(effectiveCapMicroCents),
                PerUserBudgetCounters.microCentsToUsd(spent),
                counters.remainingWindowSeconds(spendKey)
            );
        }
    }

    /** Base per-modality cap plus any live paid top-up credit for this window. */
    private long effectiveCapMicroCents(BudgetConfig config, Modality modality, String tenantId, String userKey) {
        return config.capMicroCents(modality) + counters.currentCreditMicroCents(tenantId, userKey, modality, config.windowSeconds());
    }

    /**
     * Post-call spend record. Adds {@code costUsd} (converted to micro-cents)
     * to this (tenant, user, modality) window, setting the window expiry on
     * the write that creates the counter. Never throws and never rejects - by
     * the time this runs the money is already spent; {@link #checkBudget} is
     * what blocks the next call. A null/non-positive cost, or a cost that
     * rounds below one micro-cent, is a no-op.
     */
    public void recordSpend(String tenantId, String userKey, Modality modality, BigDecimal costUsd) {
        recordSpend(tenantId, userKey, modality, costUsd, null);
    }

    /**
     * Same as {@link #recordSpend(String, String, Modality, BigDecimal)} but
     * reuses an already-resolved {@link TenantPlan} for this turn instead of
     * re-querying it - see {@link #checkBudget(String, String, Modality,
     * TenantPlan)}'s javadoc for the full dedupe rationale. {@code null}
     * falls back to the internal lookup.
     */
    public void recordSpend(String tenantId, String userKey, Modality modality, BigDecimal costUsd, TenantPlan knownPlan) {
        if (tenantId == null || costUsd == null || costUsd.signum() <= 0) {
            return;
        }
        BudgetConfig config = configResolver.resolveConfig(tenantId, userKey, knownPlan);
        if (!config.enabled()) {
            return;
        }
        long microCents = costUsd.multiply(MICRO_CENTS_PER_USD).setScale(0, RoundingMode.HALF_UP).longValueExact();
        if (microCents <= 0) {
            return;
        }
        counters.incrementBy(PerUserBudgetCounters.key(tenantId, userKey, modality), Duration.ofSeconds(config.windowSeconds()), microCents);
    }

    /**
     * Convenience for the post-call advisor: derive the call's cost from its
     * usage metadata (via {@link LlmPricingCatalog}, falling back to the
     * configured per-1k-token rate for a hosted model the catalog can't
     * price) and record it. Keeps cost derivation in one place so the advisor
     * stays a thin usage-metadata reader.
     */
    public void recordSpendForCall(
        String tenantId, String userKey, Modality modality, String model, Long promptTokens, Long completionTokens
    ) {
        recordSpendForCall(tenantId, userKey, modality, model, promptTokens, completionTokens, null);
    }

    /**
     * Same as {@link #recordSpendForCall(String, String, Modality, String,
     * Long, Long)} but reuses an already-resolved {@link TenantPlan} for this
     * turn instead of re-querying it - see {@link #checkBudget(String,
     * String, Modality, TenantPlan)}'s javadoc for the full dedupe rationale.
     * {@code null} falls back to the internal lookup.
     */
    public void recordSpendForCall(
        String tenantId, String userKey, Modality modality, String model, Long promptTokens, Long completionTokens,
        TenantPlan knownPlan
    ) {
        BigDecimal cost = pricingCatalog.estimateCostUsd(model, promptTokens, completionTokens);
        if (cost == null) {
            long totalTokens = (promptTokens == null ? 0 : promptTokens) + (completionTokens == null ? 0 : completionTokens);
            if (totalTokens <= 0) {
                return;
            }
            BudgetConfig config = configResolver.resolveConfig(tenantId, userKey, knownPlan);
            cost = config.fallbackUsdPer1kTokens()
                .multiply(BigDecimal.valueOf(totalTokens))
                .divide(BigDecimal.valueOf(1000), 8, RoundingMode.HALF_UP);
        }
        recordSpend(tenantId, userKey, modality, cost, knownPlan);
    }

    /**
     * 2026-08-08 usage-counter audit fix: same TOCTOU gap {@link
     * FreeTierUsageLimiterService#checkAndReserveTokenBudget}'s javadoc
     * describes, here for dollars instead of tokens - {@link #checkBudget} is
     * a pure read against what {@link #recordSpend} accumulated from PRIOR
     * calls, with no atomic link to THIS call's own eventual cost. A burst of
     * concurrent turns near the cap can all read "under budget" before any of
     * their real spend lands. Closes it the same way: atomically {@code
     * INCRBY} a conservative cost estimate FIRST, then compare the resulting
     * total against the effective cap, undoing the reservation before
     * throwing if it would push the window over.
     *
     * <p>The model that will actually serve this turn isn't chosen yet at
     * this point in the caller's flow (the hosted-pool chain resolves AFTER
     * this gate runs - see {@code FreeTierChatClientService#buildChainForFreeTenant}),
     * so unlike {@link #recordSpendForCall} this can't price against a
     * specific model; it always prices {@code estimatedTokens} via {@code
     * config.fallbackUsdPer1kTokens()} - the SAME generic rate {@link
     * #recordSpendForCall} already falls back to when a model's real price is
     * unknown, just used here because the model itself isn't known yet rather
     * than because the catalog can't price it. {@link
     * #reconcileSpendReservationForCall} corrects the counter to the REAL,
     * model-specific cost once the actual model+usage are known.
     *
     * @param estimatedTokens conservative upper-bound token estimate for this
     *     call - see the caller's own derivation for how it's sized.
     * @return the number of MICRO-CENTS actually reserved - 0 means nothing
     *     was reserved (master switch off, nothing to price, or a Redis
     *     outage). Pass this to {@link #reconcileSpendReservationForCall}
     *     once the real cost is known, exactly once per reservation.
     * @throws PerUserBudgetExhaustedException if reserving would push this
     *     window's spend at/past its effective cap - nothing is left reserved.
     */
    public long checkAndReserveBudget(String tenantId, String userKey, Modality modality, long estimatedTokens, TenantPlan knownPlan) {
        if (tenantId == null || estimatedTokens <= 0) {
            return 0L;
        }
        BudgetConfig config = configResolver.resolveConfig(tenantId, userKey, knownPlan);
        if (!config.enabled()) {
            return 0L;
        }
        BigDecimal estimatedCost = config.fallbackUsdPer1kTokens()
            .multiply(BigDecimal.valueOf(estimatedTokens))
            .divide(BigDecimal.valueOf(1000), 8, RoundingMode.HALF_UP);
        long reservedMicroCents = estimatedCost.multiply(MICRO_CENTS_PER_USD).setScale(0, RoundingMode.HALF_UP).longValueExact();
        if (reservedMicroCents <= 0) {
            return 0L;
        }
        String spendKey = PerUserBudgetCounters.key(tenantId, userKey, modality);
        Duration window = Duration.ofSeconds(config.windowSeconds());
        Long newTotal = counters.incrementByAndGet(spendKey, window, reservedMicroCents);
        if (newTotal == null) {
            return 0L; // Redis outage - fail open, nothing reserved.
        }
        long effectiveCapMicroCents = effectiveCapMicroCents(config, modality, tenantId, userKey);
        if (newTotal > effectiveCapMicroCents) {
            counters.incrementByAndGet(spendKey, window, -reservedMicroCents);
            counters.notifyBudgetExhausted(spendKey, config.windowSeconds(), tenantId, userKey, modality, effectiveCapMicroCents);
            throw new PerUserBudgetExhaustedException(
                counters.budgetMessage(config, modality, effectiveCapMicroCents, spendKey),
                modality.name().toLowerCase(java.util.Locale.ROOT),
                PerUserBudgetCounters.microCentsToUsd(effectiveCapMicroCents),
                PerUserBudgetCounters.microCentsToUsd(newTotal - reservedMicroCents),
                counters.remainingWindowSeconds(spendKey)
            );
        }
        return reservedMicroCents;
    }

    /**
     * Corrects a reservation {@link #checkAndReserveBudget} made to the REAL,
     * model-specific cost once it's known - same shape as {@link
     * #recordSpendForCall}'s cost derivation (catalog price, falling back to
     * the configured per-1k-token rate), but ADDS THE SIGNED DELTA against
     * {@code reservedMicroCents} instead of the raw cost. Call exactly once
     * per reservation: on the turn's real success (with the real model+usage)
     * OR its final failure (with null tokens, releasing the reservation in
     * full) - never both, and never for an intermediate provider-chain retry
     * that isn't the turn's final outcome (see {@code
     * FreeTierChatClientService}'s call site for exactly where each fires).
     *
     * <p>{@code reservedMicroCents <= 0} means nothing was actually reserved
     * for THIS call (master switch off, nothing to price, a Redis outage at
     * reservation time, or a caller that never reserved at all - e.g. a
     * self-healed retry link, see {@link
     * FreeTierUsageLimiterService#reconcileTokenReservation}'s identical
     * note) - falls back to plain additive {@link #recordSpendForCall} in
     * that case so real spend is never silently dropped just because there
     * was no reservation to reconcile against.
     */
    public void reconcileSpendReservationForCall(
        String tenantId, String userKey, Modality modality, long reservedMicroCents,
        String model, Long promptTokens, Long completionTokens, TenantPlan knownPlan
    ) {
        if (reservedMicroCents <= 0) {
            recordSpendForCall(tenantId, userKey, modality, model, promptTokens, completionTokens, knownPlan);
            return;
        }
        if (tenantId == null) {
            return;
        }
        BigDecimal cost = pricingCatalog.estimateCostUsd(model, promptTokens, completionTokens);
        if (cost == null) {
            long totalTokens = (promptTokens == null ? 0 : promptTokens) + (completionTokens == null ? 0 : completionTokens);
            BudgetConfig config = configResolver.resolveConfig(tenantId, userKey, knownPlan);
            cost = totalTokens <= 0
                ? BigDecimal.ZERO
                : config.fallbackUsdPer1kTokens()
                    .multiply(BigDecimal.valueOf(totalTokens))
                    .divide(BigDecimal.valueOf(1000), 8, RoundingMode.HALF_UP);
        }
        applySpendDelta(tenantId, userKey, modality, reservedMicroCents, cost, knownPlan);
    }

    /**
     * {@link Usage}-carrying twin of {@link #reconcileSpendReservationForCall}
     * (2026-10-05 cost-tracing audit): the token-pair overload prices only
     * prompt+completion, so a call's billable cache-write/read line items
     * (Anthropic - {@link CacheTokenAccounting#billableCacheWriteTokens()}) and
     * its out-of-completion reasoning tokens (Gemini thinking - {@link
     * ReasoningTokenAccounting#billableReasoningTokens()}) never reached the
     * dollar budget even though {@code llm_call_log} and {@code llm.cost.usd}
     * charge them. The budget therefore under-debited exactly the calls the
     * durable row priced highest. Deriving the billable buckets off the {@link
     * Usage} here - the same accessors {@code LlmMetricsAdvisor} uses - keeps
     * the debited amount and the logged amount on the same arithmetic.
     *
     * <p>{@code usage} may be {@code null} (a failed/cancelled call with no
     * usage metadata): same as the token-pair overload's null counts - the
     * reservation is released in full.
     */
    public void reconcileSpendReservationForUsage(
        String tenantId, String userKey, Modality modality, long reservedMicroCents,
        String model, Usage usage, TenantPlan knownPlan
    ) {
        if (reservedMicroCents <= 0) {
            recordSpendForUsage(tenantId, userKey, modality, model, usage, knownPlan);
            return;
        }
        if (tenantId == null) {
            return;
        }
        applySpendDelta(tenantId, userKey, modality, reservedMicroCents,
            costForUsage(tenantId, userKey, modality, model, usage, knownPlan), knownPlan);
    }

    /**
     * {@link Usage}-carrying twin of {@link #recordSpendForCall}: the plain
     * additive post-call record when there is no reservation to correct.
     */
    public void recordSpendForUsage(
        String tenantId, String userKey, Modality modality, String model, Usage usage, TenantPlan knownPlan
    ) {
        if (tenantId == null || usage == null) {
            return;
        }
        recordSpend(tenantId, userKey, modality,
            costForUsage(tenantId, userKey, modality, model, usage, knownPlan), knownPlan);
    }

    /**
     * The real dollar cost of one call's {@link Usage}: the catalog's full
     * estimate (base prompt+completion PLUS billable cache buckets and
     * out-of-completion reasoning), falling back to the configured per-1k-token
     * rate over the provider's own total when the catalog can't price it -
     * the same fallback {@link #recordSpendForCall} uses, just fed from
     * {@code totalTokens} (then the token sum) rather than only the token pair.
     */
    private BigDecimal costForUsage(
        String tenantId, String userKey, Modality modality, String model, Usage usage, TenantPlan knownPlan
    ) {
        if (usage == null) {
            // Nothing measured (failed/cancelled call) - a null cost reads as
            // 0 micro-cents to applySpendDelta, i.e. a full reservation release.
            return null;
        }
        Long promptTokens = usage.getPromptTokens() == null ? null : usage.getPromptTokens().longValue();
        Long completionTokens = usage.getCompletionTokens() == null ? null : usage.getCompletionTokens().longValue();
        CacheTokenAccounting cache = CacheTokenAccounting.from(usage);
        ReasoningTokenAccounting reasoning = ReasoningTokenAccounting.from(usage);
        BigDecimal cost = pricingCatalog.estimateCostUsd(
            null, model, promptTokens, completionTokens,
            cache.billableCacheWriteTokens(), cache.billableCacheReadTokens(),
            reasoning.billableReasoningTokens());
        if (cost != null) {
            return cost;
        }
        long totalTokens = usage.getTotalTokens() != null
            ? usage.getTotalTokens().longValue()
            : (promptTokens == null ? 0 : promptTokens) + (completionTokens == null ? 0 : completionTokens);
        if (totalTokens <= 0) {
            return BigDecimal.ZERO;
        }
        BudgetConfig config = configResolver.resolveConfig(tenantId, userKey, knownPlan);
        return config.fallbackUsdPer1kTokens()
            .multiply(BigDecimal.valueOf(totalTokens))
            .divide(BigDecimal.valueOf(1000), 8, RoundingMode.HALF_UP);
    }

    /**
     * The tail of every reconcile path: converts {@code actualCost} to
     * micro-cents and applies the signed delta against {@code
     * reservedMicroCents} to this window's counter.
     */
    private void applySpendDelta(
        String tenantId, String userKey, Modality modality, long reservedMicroCents,
        BigDecimal actualCost, TenantPlan knownPlan
    ) {
        applySpendDelta(tenantId, userKey, modality, reservedMicroCents, actualCost, knownPlan, false);
    }

    /**
     * @param keepReservation true when {@code actualCost} is only an estimate of what a response cost: the reservation
     *     then stays as a floor and the charge can only grow, never shrink below what was reserved.
     */
    private void applySpendDelta(
        String tenantId, String userKey, Modality modality, long reservedMicroCents,
        BigDecimal actualCost, TenantPlan knownPlan, boolean keepReservation
    ) {
        long actualMicroCents = actualCost == null || actualCost.signum() <= 0 ? 0L
            : actualCost.multiply(MICRO_CENTS_PER_USD).setScale(0, RoundingMode.HALF_UP).longValueExact();
        long delta = actualMicroCents - reservedMicroCents;
        if (keepReservation && delta < 0) {
            return;
        }
        if (delta == 0) {
            return;
        }
        BudgetConfig config = configResolver.resolveConfig(tenantId, userKey, knownPlan);
        counters.incrementBy(PerUserBudgetCounters.key(tenantId, userKey, modality), Duration.ofSeconds(config.windowSeconds()), delta);
    }

    /** A model writes about four characters per token; used only when the provider reported no usage at all. */
    static final double CHARS_PER_TOKEN = 4.0;

    /**
     * A response that was produced and shown but came back without usage metadata (a provider that omits it, a stream
     * cancelled before the usage event). Releasing the reservation here, as a zero-token call would, made the call free:
     * a user could cancel every stream after the text was out and spend nothing. The reservation is kept as the
     * charge, raised to the size of what was actually received if that is more; with no reservation (a sibling call of
     * the same turn) the received text is charged at the fallback rate.
     */
    public void chargeUnmeasuredResponse(
        String tenantId, String userKey, Modality modality, long reservedMicroCents, long receivedChars,
        TenantPlan knownPlan
    ) {
        if (tenantId == null || receivedChars <= 0) {
            return;
        }
        BigDecimal estimated = estimateCostForChars(tenantId, userKey, receivedChars, knownPlan);
        if (reservedMicroCents > 0) {
            applySpendDelta(tenantId, userKey, modality, reservedMicroCents, estimated, knownPlan, true);
        } else {
            recordSpend(tenantId, userKey, modality, estimated, knownPlan);
        }
    }

    /** The output-token estimate for {@code receivedChars} of text, priced at the fallback rate. */
    BigDecimal estimateCostForChars(String tenantId, String userKey, long receivedChars, TenantPlan knownPlan) {
        long tokens = (long) Math.ceil(receivedChars / CHARS_PER_TOKEN);
        return configResolver.resolveConfig(tenantId, userKey, knownPlan).fallbackUsdPer1kTokens()
            .multiply(BigDecimal.valueOf(tokens))
            .divide(BigDecimal.valueOf(1000), 8, RoundingMode.HALF_UP);
    }

    /**
     * Full-release convenience for {@link #reconcileSpendReservationForCall}
     * when a turn ends with NO usage at all to report (the whole
     * provider-chain attempt failed before any model produced usage
     * metadata) - equivalent to calling it with null tokens, spelled out
     * separately so {@code FreeTierChatClientService}'s failure-path call
     * sites don't need to fabricate a model name for a call that never
     * happened.
     */
    public void releaseSpendReservation(String tenantId, String userKey, Modality modality, long reservedMicroCents, TenantPlan knownPlan) {
        reconcileSpendReservationForCall(tenantId, userKey, modality, reservedMicroCents, null, null, null, knownPlan);
    }

    // ---- P2 paid top-up / overage -----------------------------------------

    /**
     * PLATFORM-ADMIN-GOVERNANCE-AND-CONTROLS.md §4 P2 - credits {@code
     * usdAmount} of PAID budget to the CURRENT window for this (tenant, user,
     * modality). Adds to a separate credit counter that {@link #checkBudget}
     * folds into the effective cap, so the next turn passes the gate without a
     * plan change; the credit expires with the window (aligned to the spend
     * counter's remaining TTL when one exists, otherwise the configured window
     * length) so unused top-up never carries silently into the next day.
     *
     * <p>Called only from the payment-confirm path (a successful, signature-
     * verified top-up payment), never from the chat hot path. Unlike the
     * metering reads/writes elsewhere in this class, this deliberately does
     * <b>not</b> fail open: by the time it runs the customer has already been
     * charged, so a Redis failure here must surface to the caller (which can
     * then reconcile / retry) rather than silently swallow paid credit. A
     * null/non-positive amount, or one that rounds below one micro-cent, is a
     * no-op.
     *
     * <p>If a failure ever does land between the {@code INCRBY} below and its
     * {@code EXPIRE} (e.g. this JVM dies mid-call, after the caller already
     * saw success from a prior partial retry), {@link PerUserBudgetCounters#currentCreditMicroCents}
     * self-heals it on its next read the same way it does for the spend
     * counter - see {@code PerUserBudgetTtlRepair#repairMissingTtl}'s javadoc.
     */
    public void creditTopUp(String tenantId, String userKey, Modality modality, BigDecimal usdAmount) {
        if (tenantId == null || modality == null || usdAmount == null || usdAmount.signum() <= 0) {
            return;
        }
        long microCents = usdAmount.multiply(MICRO_CENTS_PER_USD).setScale(0, RoundingMode.HALF_UP).longValueExact();
        if (microCents <= 0) {
            return;
        }
        String creditKey = PerUserBudgetCounters.creditKey(tenantId, userKey, modality);
        redisTemplate.opsForValue().increment(creditKey, microCents);
        // Align the credit's expiry to the spend window in progress so top-up
        // and spend reset together; if no spend counter exists yet (nothing
        // spent this window), fall back to a fresh full window.
        Long spendTtl = counters.remainingWindowSeconds(PerUserBudgetCounters.key(tenantId, userKey, modality));
        long ttlSeconds = (spendTtl != null && spendTtl > 0) ? spendTtl : configResolver.resolveConfig(tenantId, userKey).windowSeconds();
        redisTemplate.expire(creditKey, Duration.ofSeconds(ttlSeconds));
        log.info("Credited ${} per-user {} top-up for tenant '{}'", usdAmount.toPlainString(), modality.name().toLowerCase(java.util.Locale.ROOT), tenantId);
    }

    /**
     * Current effective status of a (tenant, user, modality) window - the
     * effective cap (base + live top-up credit), spend so far, remaining
     * headroom (never negative), and seconds to reset. Used by the top-up
     * confirm response so a client can immediately show the restored budget.
     */
    public BudgetStatus status(String tenantId, String userKey, Modality modality) {
        BudgetConfig config = configResolver.resolveConfig(tenantId, userKey);
        long cap = effectiveCapMicroCents(config, modality, tenantId, userKey);
        long spent = counters.currentSpendMicroCents(tenantId, userKey, modality, config.windowSeconds());
        long remaining = Math.max(0L, cap - spent);
        return new BudgetStatus(
            modality.name().toLowerCase(java.util.Locale.ROOT),
            PerUserBudgetCounters.microCentsToUsd(cap),
            PerUserBudgetCounters.microCentsToUsd(spent),
            PerUserBudgetCounters.microCentsToUsd(remaining),
            counters.remainingWindowSeconds(PerUserBudgetCounters.key(tenantId, userKey, modality))
        );
    }

    /** Snapshot of a per-user window's dollar state, all amounts in USD. */
    public record BudgetStatus(String modality, BigDecimal capUsd, BigDecimal spentUsd, BigDecimal remainingUsd, Long resetSeconds) {
    }

    // ---- config read/write (admin surface) -------------------------------

    /** Current effective global config - what the admin GET endpoint returns. */
    public BudgetConfig currentGlobalConfig() {
        return configResolver.loadGlobalConfig();
    }

    /**
     * Admin write path - upserts each provided (non-null) field as its {@code
     * platform_setting} row, then busts the snapshot cache so the change is
     * live on the next turn without a redeploy. Nulls leave that field's row
     * untouched (partial update), mirroring {@code
     * PlatformLlmTaskService#upsert}'s optional-field posture.
     */
    public BudgetConfig updateGlobalConfig(
        Boolean enabled,
        BigDecimal dailyUsd,
        Integer windowSeconds,
        Map<Modality, Integer> splitPct,
        BigDecimal fallbackUsdPer1kTokens,
        String updatedBy
    ) {
        return configResolver.updateGlobalConfig(enabled, dailyUsd, windowSeconds, splitPct, fallbackUsdPer1kTokens, updatedBy);
    }

    /**
     * Proactive keyspace sweep, called once a day by {@code
     * DailyQuotaResetScheduler}'s idempotent run - same purpose as {@link
     * FreeTierUsageLimiterService#repairAllMissingTtls}: unlike the lazy
     * repair inside {@link PerUserBudgetCounters#currentSpendMicroCents}/{@link
     * PerUserBudgetCounters#currentCreditMicroCents} (which only fixes a counter the next time
     * something happens to read that exact key), this walks every existing
     * spend and paid-credit counter, across every modality and tenant/user,
     * and repairs any that lost its TTL. Uses {@code SCAN} (cursor-based,
     * incremental) rather than {@code KEYS} - verify-pass fix: {@code KEYS}
     * is O(N) over the ENTIRE keyspace of the Redis DB (not just matching
     * keys) and blocks Redis's single-threaded event loop for its full
     * duration, stalling every other tenant's live rate-limiting/session
     * traffic on a shared instance; {@code SCAN} walks the same keyspace
     * incrementally without that blocking cost. Returns the number of
     * counters repaired.
     */
    public int repairAllMissingTtls() {
        int repaired = 0;
        try {
            // Verify-pass fix: this call was unprotected - a transient
            // failure reading platform config (platformSettingsService) would
            // throw out of this method entirely, aborting the sweep before
            // either prefix was even attempted and, when called from
            // DailyQuotaResetScheduler, propagating up to the caller.
            int windowSeconds = configResolver.effectiveGlobalConfig().windowSeconds();
            repaired += counters.repairAllPrefixes(windowSeconds);
        } catch (RuntimeException e) {
            log.debug("Per-user budget TTL repair sweep failed to load config - leaving for the next lazy read to retry", e);
        }
        return repaired;
    }


    /**
     * Effective, resolved budget config. {@code splitPct} is each modality's
     * cap as a percentage of {@code dailyUsd} - independent sub-caps, so 100%
     * for several modalities means each may independently spend up to the full
     * daily amount (they are separate gates, not a shared pool).
     */
    public record BudgetConfig(
        boolean enabled,
        BigDecimal dailyUsd,
        int windowSeconds,
        Map<Modality, Integer> splitPct,
        BigDecimal fallbackUsdPer1kTokens
    ) {
        /** This modality's cap in micro-cents: {@code dailyUsd * split%/100 * 1e8}. */
        public long capMicroCents(Modality modality) {
            int pct = splitPct.getOrDefault(modality, 0);
            return dailyUsd
                .multiply(BigDecimal.valueOf(pct))
                .divide(BigDecimal.valueOf(100), 8, RoundingMode.HALF_UP)
                .multiply(MICRO_CENTS_PER_USD)
                .setScale(0, RoundingMode.HALF_UP)
                .longValueExact();
        }
    }

}
