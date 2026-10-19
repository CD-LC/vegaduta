package com.agenticai.core.agents.quota;

import com.agenticai.core.agents.budget.PerUserBudgetService;
import com.agenticai.core.audit.NotificationCategory;
import com.agenticai.core.audit.NotificationService;
import com.agenticai.core.audit.NotificationSeverity;
import com.agenticai.core.security.TenantLimitExceededException;
import com.agenticai.core.security.TenantPlan;
import com.agenticai.core.tenancy.PlatformSettingsService;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Monthly token cap for tenants on the platform-hosted LLM pool (see {@code
 * PlatformHostedLlmProvider}/{@code HostedLlmChatClientFactory}) - this is the
 * platform's own money being spent per call (unlike BYOK, where the tenant's
 * own key/wallet absorbs cost), so unlike every other feature in this
 * codebase, hosted-pool calls need an enforced usage ceiling, not just a
 * count-of-things cap like {@code TenantLimits#maxAgents}.
 *
 * <p><b>2026-09-26 simplification (product-owner direction).</b> This class
 * previously enforced FOUR independent fixed windows - daily + monthly
 * MESSAGE counts (30/300, plan-scaled - the "0/90 today" pill on UNLIMITED)
 * plus daily + monthly TOKEN counts. The message windows were a proxy for
 * cost that the token window already meters truthfully, and the daily token
 * window duplicated the monthly one's job of bounding platform spend. All
 * three are removed: what remains is ONE rolling-30-day token counter per
 * tenant, matching how competitors meter hosted usage (single unit, single
 * window). Message-charge refund plumbing ({@code checkAndConsume}/
 * {@code refundMessage}) went away with the counter it compensated; the
 * token reservation below already handles every failure path via {@link
 * #reconcileTokenReservation}. Burst abuse is a different control
 * ({@code LlmGatewayRateLimiterService}); per-USER spend fairness is a
 * different control ({@link PerUserBudgetService}); BYOK monthly USD is a
 * different control ({@code LlmGatewayBudgetService}). This class is only
 * the platform-pool token quota.
 *
 * <p>Same Redis {@code INCRBY} + conditional {@code EXPIRE} fixed-window
 * pattern as {@code com.agenticai.core.tenancy.SignupRateLimiter} - see that
 * class's javadoc for the known "not perfectly atomic, acceptable for an
 * abuse-throttle-shaped check" limitation, which applies here identically.
 * The window is a rolling 30 days from first write, not a calendar month.
 *
 * <p><b>Admin-tunable, no redeploy</b> (2026-08-08 hardening, same pattern
 * {@link PerUserBudgetService} already established via V117): {@code
 * monthlyTokenLimit}/{@code premiumMultiplier}/{@code unlimitedMultiplier}
 * are read from {@link PlatformSettingsService} ({@code platform_setting}
 * rows seeded by {@code V194__free_tier_quota_settings.sql}, falling back to
 * the constructor-injected {@code @Value} defaults below when a row is
 * absent), with a small time-boxed cache ({@code
 * app.free-tier.quota-config-cache-ms}, default 10s) keeping this off Postgres
 * on the chat hot path - identical caching shape to {@code
 * PerUserBudgetService#effectiveGlobalConfig}. Default 400,000/month FREE
 * base &times; 3 = 1,200,000 tokens/month for UNLIMITED.
 *
 * <p><b>Plan scaling</b> (direct feedback 2026-07-26): PREMIUM and UNLIMITED
 * multiply the FREE-tier base (2x/3x by default), so changing the FREE
 * default rescales both paid tiers automatically. UNLIMITED is not literally
 * uncapped for THIS platform-hosted-pool quota - the plan name still means
 * uncapped for maxAgents/maxUsers/maxWorkflows ({@code PlanLimitsService}) -
 * this cap exists purely to bound the platform's own hosted-provider spend
 * per tenant.
 */
@Component
public class FreeTierUsageLimiterService {

    private static final Logger log = LoggerFactory.getLogger(FreeTierUsageLimiterService.class);

    private static final Duration MONTHLY_WINDOW = Duration.ofDays(30);
    private static final String MONTHLY_TOKEN_KEY_PREFIX = "agentic:free-tier-usage:monthly-tokens:";
    // Infra audit (2026-07-27) - same shared metric name/shape as
    // com.agenticai.core.chat.gateway.LlmGatewayBudgetService/
    // LlmGatewayRateLimiterService's rejection counters (different module,
    // same "one metric name, a `reason` tag distinguishes which check
    // tripped" convention - no direct code dependency between the two, this
    // module doesn't depend on core.chat.gateway).
    private static final String REJECTIONS_METRIC = "llm.gateway.rejections";

    // 2026-08-10 incident fix: matches AgentCreateRequest/
    // AgentGenerationParamsUpdateRequest's own write-time @Max bound and
    // GenerationParamsInferenceService's MAX_MAX_TOKENS - see
    // checkAndReserveTokenBudget's @param maxTokens doc for why this needs to
    // ALSO be enforced here, not just at write time.
    private static final long MAX_REASONABLE_MAX_TOKENS = 8192;

    // PlatformSetting keys - the monthly/multiplier rows V194 seeds (the
    // daily row it also seeded is retired and deleted by V354). Kept public
    // so the admin controller/UI and any test can reference the exact same
    // strings - same convention as PerUserBudgetService's own KEY_* constants.
    public static final String KEY_MONTHLY_TOKEN_LIMIT = "platform.hosted-llm-quota.monthly-token-limit";
    public static final String KEY_PREMIUM_MULTIPLIER = "platform.hosted-llm-quota.premium-multiplier";
    public static final String KEY_UNLIMITED_MULTIPLIER = "platform.hosted-llm-quota.unlimited-multiplier";

    private final StringRedisTemplate redisTemplate;
    private final PlatformSettingsService platformSettingsService;
    private final long configCacheMs;
    // Constructor-injected @Value default - the fallback when no
    // platform_setting row exists yet (a database that predates V194), same
    // "the service works even against a database that predates the seed
    // migration" posture PerUserBudgetService's javadoc documents for itself.
    // The LIVE effective value is read via effectiveQuotaConfig() below.
    private final long defaultMonthlyTokenLimit;
    private final MeterRegistry meterRegistry;
    // ObjectProvider: the limit-hit bell (Tier-3) is a best-effort side
    // signal - TenantLimitExceededException is the enforcement, the
    // notification is just how a tenant-admin finds out without polling.
    private final ObjectProvider<NotificationService> notificationService;
    private final int defaultPremiumMultiplier;
    private final int defaultUnlimitedMultiplier;
    private final long tokenReservationPromptEstimate;

    /**
     * {@code app.edition.billing-enabled}. Off (personal profile): usage is still counted so the cost views
     * stay truthful, but the monthly ceiling never rejects a turn and never tells anyone to upgrade - there is
     * no plan to upgrade to. Field-injected with a default of {@code true} so direct construction (tests)
     * keeps today's behaviour.
     */
    @Value("${app.edition.billing-enabled:true}")
    private boolean billingEdition = true;

    private final AtomicReference<CachedQuotaConfig> cachedQuotaConfig = new AtomicReference<>();

    public FreeTierUsageLimiterService(
        StringRedisTemplate redisTemplate,
        PlatformSettingsService platformSettingsService,
        // #972 - "there should be limit on the tokens ... they send."
        // Fallback default used only when no platform_setting row exists yet
        // - see this class's top-level javadoc "Admin-tunable" section; the
        // live effective value comes from effectiveQuotaConfig().
        @Value("${app.free-tier.monthly-token-limit:400000}") long monthlyTokenLimit,
        @Value("${app.platform-llm.premium-multiplier:2}") int premiumMultiplier,
        @Value("${app.platform-llm.unlimited-multiplier:3}") int unlimitedMultiplier,
        MeterRegistry meterRegistry,
        // 2026-08-08 usage-counter audit fix: checkAndReserveTokenBudget's
        // reservation amount is maxTokens (a real, provider-enforced ceiling
        // on the completion side) PLUS this fixed estimate for the prompt
        // side, which has no equivalent hard ceiling at this layer. A round,
        // conservative number - see that method's javadoc for why an
        // over-estimate is the safe direction (reconcileTokenReservation
        // corrects it to the real total once known).
        @Value("${app.free-tier.token-reservation-prompt-estimate:2000}") long tokenReservationPromptEstimate,
        @Value("${app.free-tier.quota-config-cache-ms:10000}") long configCacheMs,
        ObjectProvider<NotificationService> notificationService
    ) {
        this.redisTemplate = redisTemplate;
        this.platformSettingsService = platformSettingsService;
        this.defaultMonthlyTokenLimit = monthlyTokenLimit;
        this.defaultPremiumMultiplier = premiumMultiplier;
        this.defaultUnlimitedMultiplier = unlimitedMultiplier;
        this.meterRegistry = meterRegistry;
        this.tokenReservationPromptEstimate = tokenReservationPromptEstimate;
        this.configCacheMs = configCacheMs;
        this.notificationService = notificationService;
    }

    private int multiplierFor(TenantPlan plan) {
        QuotaConfig config = effectiveQuotaConfig();
        return plan == TenantPlan.UNLIMITED ? config.unlimitedMultiplier() : config.premiumMultiplier();
    }

    /**
     * Notifications (Tier-3): the tenant bell for a quota hit - the audit's
     * "budget alerts require polling the console" gap, broadcast to
     * tenant-admins (recipientUserId null). Deduped once per
     * (tenant, limit-kind, window): every check throws the SAME exception
     * on every gated call and a retrying user would otherwise mint a
     * notification per retry, so an NX Redis key under the window's own TTL
     * is the whole "once per window" mechanism. Best-effort end to end - a
     * bell-write or Redis failure must never change the limit decision,
     * and a Redis outage fails OPEN on the notify side (worst case one
     * extra bell), exactly the inverse of the enforcement side's posture.
     */
    private void notifyLimitHit(String tenantId, String reasonTag, Duration dedupeWindow, String title, String body) {
        try {
            Boolean firstThisWindow = redisTemplate.opsForValue().setIfAbsent(
                "free_tier_limit_notified:" + tenantId + ":" + reasonTag, "1", dedupeWindow);
            if (!Boolean.TRUE.equals(firstThisWindow)) {
                return;
            }
            NotificationService service = notificationService.getIfAvailable();
            if (service != null) {
                service.create(null, NotificationCategory.BILLING, title, body,
                    NotificationSeverity.WARNING, null, null);
            }
        } catch (RuntimeException notifyFailure) {
            // Best-effort by convention - see this method's javadoc.
        }
    }

    // ---- admin-tunable quota config (platform_setting-backed) -------------

    /** Current effective quota config - what a future admin GET endpoint would return. */
    public QuotaConfig currentQuotaConfig() {
        return effectiveQuotaConfig();
    }

    /**
     * Admin write path - upserts each provided (non-null) field as its {@code
     * platform_setting} row, then busts the snapshot cache so the change is
     * live on the next turn without a redeploy. Nulls leave that field's row
     * untouched (partial update) - identical shape to {@link
     * PerUserBudgetService#updateGlobalConfig}.
     */
    public QuotaConfig updateQuotaConfig(
        Long monthlyTokenLimit, Integer premiumMultiplier, Integer unlimitedMultiplier, String updatedBy
    ) {
        if (monthlyTokenLimit != null) {
            platformSettingsService.update(KEY_MONTHLY_TOKEN_LIMIT, Long.toString(monthlyTokenLimit), updatedBy);
        }
        if (premiumMultiplier != null) {
            platformSettingsService.update(KEY_PREMIUM_MULTIPLIER, Integer.toString(premiumMultiplier), updatedBy);
        }
        if (unlimitedMultiplier != null) {
            platformSettingsService.update(KEY_UNLIMITED_MULTIPLIER, Integer.toString(unlimitedMultiplier), updatedBy);
        }
        cachedQuotaConfig.set(null);
        return effectiveQuotaConfig();
    }

    /** Admin-tunable token-quota knobs: base FREE-tier monthly token limit plus the PREMIUM/UNLIMITED plan multipliers. */
    public record QuotaConfig(long monthlyTokenLimit, int premiumMultiplier, int unlimitedMultiplier) {
    }

    /**
     * Manual admin override for a tenant stuck against its monthly cap
     * (support-desk action: "raise this tenant's limit" is {@link
     * #updateQuotaConfig} - a global change; this is the narrower "give THIS
     * one tenant a clean slate right now" action, e.g. after a confirmed
     * metering bug like the {@code FreeTierTokenTrackingAdvisor} cancel-leak
     * fixed 2026-08-09). Deletes the fixed-window Redis counter outright,
     * rather than decrementing - there is no "correct" partial value to reset
     * to, and a delete is unambiguous and instantly verifiable. A missing key
     * (a tenant already under quota) is a silent no-op, same fail-open
     * posture every write in this class takes. Retired message/daily-token
     * key names are deliberately not deleted here: nothing reads them any
     * more and their own TTLs expire them within 30 days at the latest.
     */
    public void resetTenantUsage(String tenantId) {
        redisTemplate.delete(MONTHLY_TOKEN_KEY_PREFIX + tenantId);
        log.info("Hosted-pool token quota manually reset for tenant '{}' (platform-admin action)", tenantId);
    }

    private record CachedQuotaConfig(QuotaConfig config, long loadedAtMs) {
    }

    /**
     * Time-boxed cache (default 10s, {@code app.free-tier.quota-config-cache-ms})
     * around {@link PlatformSettingsService} reads - identical shape and
     * reasoning to {@code PerUserBudgetService#effectiveGlobalConfig}: this is
     * read on every {@link #checkAndReserveTokenBudget} call (the chat hot
     * path), so an uncached read would add a Postgres round trip to every
     * message.
     */
    private QuotaConfig effectiveQuotaConfig() {
        CachedQuotaConfig cached = cachedQuotaConfig.get();
        long now = System.currentTimeMillis();
        if (cached != null && (now - cached.loadedAtMs()) < configCacheMs) {
            return cached.config();
        }
        QuotaConfig fresh = new QuotaConfig(
            platformSettingsService.getLong(KEY_MONTHLY_TOKEN_LIMIT, defaultMonthlyTokenLimit),
            platformSettingsService.getInt(KEY_PREMIUM_MULTIPLIER, defaultPremiumMultiplier),
            platformSettingsService.getInt(KEY_UNLIMITED_MULTIPLIER, defaultUnlimitedMultiplier)
        );
        cachedQuotaConfig.set(new CachedQuotaConfig(fresh, now));
        return fresh;
    }

    /**
     * Post-call token-budget update - called once the real {@code
     * ChatResponse} usage metadata is known (see {@code
     * FreeTierTokenTrackingAdvisor} in this package), adding {@code
     * totalTokens} to the monthly window. Deliberately never throws - by the
     * time this runs the call has already happened and the tokens are already
     * spent, so there is nothing left to reject; {@link
     * #checkAndReserveTokenBudget} is what prevents the NEXT call once this
     * pushes a tenant over. {@code totalTokens <= 0} is a no-op (a failed
     * call with no usage metadata, or a provider that didn't report usage -
     * nothing to add).
     */
    public void recordTokenUsage(String tenantId, long totalTokens) {
        if (totalTokens <= 0) {
            return;
        }
        incrementBy(MONTHLY_TOKEN_KEY_PREFIX + tenantId, MONTHLY_WINDOW, totalTokens);
    }

    /**
     * Atomic reserve-then-check gate (2026-08-08 usage-counter audit fix): a
     * pure read-then-record pair has no atomic connection between this call's
     * check and its eventual usage - a burst of concurrent turns near the cap
     * can all read "under budget" before any of their real usage lands,
     * overshooting the ceiling by several multiples of a single call's cost.
     * This reserves a conservative estimate FIRST via an atomic {@code
     * INCRBY}, THEN compares the resulting total against the cap, undoing the
     * reservation before throwing if it would push the window over.
     * Concurrent callers now genuinely serialize against the SAME evolving
     * counter instead of all reading one stale snapshot.
     *
     * <p>The reservation is intentionally an OVER-estimate ({@code maxTokens}
     * - a real, provider-enforced ceiling on the completion side - plus
     * {@link #tokenReservationPromptEstimate}, a fixed stand-in for the
     * prompt side, which has no equivalent hard ceiling at this layer): the
     * safe direction to be wrong in is reserving too much, never too little.
     * {@link #reconcileTokenReservation} corrects the counter to the REAL
     * total once the actual usage is known, so an over-reservation never
     * permanently overstates the tenant's usage - only the caller's OWN
     * concurrent requests briefly see less headroom than the eventual true
     * number, for the handful of seconds a call is in flight.
     *
     * <p>Fail-open like every other write in this class: a Redis outage
     * during the reservation itself ({@link #incrementByAndGet} returning
     * {@code null}) reserves nothing and allows the call through - a
     * metering-store hiccup must never block a turn.
     *
     * @param maxTokens the caller's configured output ceiling - see the
     *     reservation-sizing note above. {@code AgentCreateRequest}/{@code
     *     AgentGenerationParamsUpdateRequest} now cap this at 8192 at write
     *     time, but this method clamps it too (2026-08-10 incident: an agent
     *     that got stored with an unbounded value before that validation
     *     existed reserved the ENTIRE stored number on every turn, rejecting
     *     every single call as "limit reached" regardless of the tenant's
     *     actual usage - a bad value already sitting in the database needs a
     *     code-level clamp here, not just a write-time gate, to self-heal
     *     without a manual data fix).
     * @return the number of tokens actually reserved - 0 means nothing was
     *     reserved (Redis outage). Pass this to {@link
     *     #reconcileTokenReservation} once the real total is known, exactly
     *     once per reservation (see {@code FreeTierChatClientService}'s call
     *     site for exactly where that fires for each outcome).
     * @throws TenantLimitExceededException if reserving would push the window
     *     at/past its effective limit - nothing is left reserved.
     */
    public long checkAndReserveTokenBudget(String tenantId, TenantPlan plan, int maxTokens) {
        boolean isPremium = plan == TenantPlan.PREMIUM;
        boolean isUnlimited = plan == TenantPlan.UNLIMITED;
        QuotaConfig quotaConfig = effectiveQuotaConfig();
        int multiplier = isUnlimited ? quotaConfig.unlimitedMultiplier() : quotaConfig.premiumMultiplier();
        long effectiveMonthlyTokenLimit = (isPremium || isUnlimited)
            ? quotaConfig.monthlyTokenLimit() * multiplier : quotaConfig.monthlyTokenLimit();
        String tierLabel = isUnlimited ? "Unlimited plan" : isPremium ? "Platform LLM" : "Free plan";
        String upgradeHint = isUnlimited ? "Contact support to raise this limit"
            : isPremium ? "Upgrade to Unlimited" : "Upgrade to Premium";

        // Clamp defensively at read time (see this method's @param maxTokens
        // doc) - MAX_REASONABLE_MAX_TOKENS matches the write-time validation
        // ceiling, so a call this size still comfortably fits inside even the
        // unscaled FREE-tier monthly budget instead of being able to exhaust
        // it in a single reservation.
        long reservation = Math.min(Math.max(0, maxTokens), MAX_REASONABLE_MAX_TOKENS) + Math.max(0, tokenReservationPromptEstimate);
        if (reservation <= 0) {
            return 0L;
        }
        String monthlyKey = MONTHLY_TOKEN_KEY_PREFIX + tenantId;

        Long monthlyTokens = incrementByAndGet(monthlyKey, MONTHLY_WINDOW, reservation);
        if (monthlyTokens == null) {
            return 0L; // Redis outage - fail open, nothing reserved.
        }
        if (billingEdition && monthlyTokens > effectiveMonthlyTokenLimit) {
            incrementByAndGet(monthlyKey, MONTHLY_WINDOW, -reservation);
            meterRegistry.counter(REJECTIONS_METRIC, "tenant", tenantId, "reason", "free_tier_monthly_token_limit").increment();
            notifyLimitHit(tenantId, "free_tier_monthly_token_limit", MONTHLY_WINDOW,
                tierLabel + " monthly token limit reached",
                "This tenant hit " + effectiveMonthlyTokenLimit + " tokens/month on the shared hosted-LLM pool. "
                    + upgradeHint + " for a higher limit.");
            throw new TenantLimitExceededException(
                tierLabel + " monthly token limit reached (" + effectiveMonthlyTokenLimit + " tokens/month) on the "
                    + "platform's shared hosted-LLM pool. This is a platform-wide usage cap, not a billing problem or a "
                    + "bug - " + upgradeHint + " for a much higher limit.");
        }
        return reservation;
    }

    /**
     * Corrects a reservation {@link #checkAndReserveTokenBudget} made to the
     * REAL token total once it's known, by adding the signed delta ({@code
     * actualTokens - reservedTokens}, which is usually negative - the
     * reservation over-estimates on purpose) to the window. Call exactly once
     * per reservation: on the turn's real success (with the real total) OR
     * its final failure (with {@code actualTokens=0}, releasing the
     * reservation in full) - never both, and never for an intermediate
     * provider-chain retry that isn't the turn's final outcome (see {@code
     * FreeTierChatClientService}'s call site for exactly where each fires).
     *
     * <p>{@code reservedTokens <= 0} means nothing was actually reserved for
     * THIS call (a Redis outage at reservation time, or a caller that never
     * reserved at all - e.g. {@code
     * FreeTierChatClientService#rebuildLinkAfterSelfHeal}'s deliberately
     * unmetered retry, see its own javadoc for why) - falls back to a plain
     * additive {@link #recordTokenUsage} in that case so real usage is never
     * silently dropped just because there was no reservation to reconcile
     * against.
     */
    public void reconcileTokenReservation(String tenantId, long reservedTokens, long actualTokens) {
        if (reservedTokens <= 0) {
            recordTokenUsage(tenantId, actualTokens);
            return;
        }
        long delta = actualTokens - reservedTokens;
        if (delta == 0) {
            return;
        }
        incrementBy(MONTHLY_TOKEN_KEY_PREFIX + tenantId, MONTHLY_WINDOW, delta);
    }

    /**
     * Read-only used-vs-limit snapshot of the enforced window for the visible
     * usage meter ({@code GET /api/tenant/usage-quota}, see {@code
     * TenantUsageQuotaController} in {@code core/app}). Lives here rather
     * than having the controller re-derive Redis keys so the key format and
     * the plan-multiplier math stay in exactly one class - this reads the
     * same counter {@link #checkAndReserveTokenBudget} enforces against, and
     * NEVER increments anything (a user opening their usage page must not
     * consume quota). Fail-open posture is inherited from {@link
     * #currentCount}: a Redis outage reads as zero used, which for a display
     * surface just means the meter briefly under-reports.
     */
    public QuotaSnapshot quotaSnapshot(String tenantId, TenantPlan plan) {
        boolean scaled = plan == TenantPlan.PREMIUM || plan == TenantPlan.UNLIMITED;
        long multiplier = scaled ? multiplierFor(plan) : 1L;
        return new QuotaSnapshot(
            currentCount(MONTHLY_TOKEN_KEY_PREFIX + tenantId, MONTHLY_WINDOW),
            effectiveQuotaConfig().monthlyTokenLimit() * multiplier
        );
    }

    /** The one enforced window as used-so-far plus the caller's plan-scaled effective limit (FREE 1x, PREMIUM 2x, UNLIMITED 3x by default). */
    public record QuotaSnapshot(long tokensUsedMonth, long tokensMonthlyLimit) {
    }

    /**
     * @param window the configured window length for this key, used only if
     *     this read discovers the counter has lost its TTL - see {@link
     *     #repairMissingTtl}.
     */
    private long currentCount(String key, Duration window) {
        String value = redisTemplate.opsForValue().get(key);
        if (value == null) {
            return 0;
        }
        repairMissingTtl(key, window);
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            // Corrupt/unexpected value - fail open (same posture as a Redis
            // outage below) rather than block a call on a parse error that's
            // almost certainly not the tenant's fault.
            return 0;
        }
    }

    /**
     * Self-heal a counter whose TTL was lost - ported from {@link
     * PerUserBudgetService#repairMissingTtl}, same failure mode and same fix:
     * {@link #incrementByAndGet} does an {@code INCRBY} followed by a separate
     * {@code EXPIRE}, and if the process dies (or Redis errors) between the
     * two, the key is left with a value but no expiry - a permanent lockout
     * for that tenant's window, since {@link #checkAndReserveTokenBudget}
     * then compares against a count that never rolls over. Deliberately a
     * read-path repair (see that class's javadoc for why) rather than an
     * atomic write - the very next read of a stuck key, from any
     * request/instance, repairs it. {@code TTL} returns {@code -1} for
     * "exists, no expiry" vs {@code -2} for "doesn't exist"; callers here
     * have already confirmed the key exists via a non-null {@code GET}, so
     * {@code -1} unambiguously means repair.
     *
     * @return whether this call actually repaired a lost TTL (used by {@link
     *     #repairAllMissingTtls} to report how many it fixed).
     */
    private boolean repairMissingTtl(String key, Duration window) {
        try {
            Long ttl = redisTemplate.getExpire(key);
            if (ttl != null && ttl == -1L) {
                redisTemplate.expire(key, window);
                log.warn("Hosted-pool token counter '{}' had no TTL (would never have reset) - repaired with a fresh {} expiry", key, window);
                return true;
            }
        } catch (RuntimeException e) {
            log.debug("Hosted-pool token TTL repair failed for '{}' - leaving for the next read to retry", key, e);
        }
        return false;
    }

    /**
     * Proactive keyspace sweep, called once a day by {@code
     * DailyQuotaResetScheduler}'s idempotent run - unlike the lazy repair
     * inside {@link #currentCount} (which only fixes a counter the next time
     * something happens to read that exact key), this walks every existing
     * monthly-token counter and repairs any that lost its TTL, even for a
     * tenant that never triggers another read on that key. Uses {@code SCAN}
     * (cursor-based, incremental) rather than {@code KEYS} - {@code KEYS} is
     * O(N) over the ENTIRE keyspace of the Redis DB (not just matching keys)
     * and blocks Redis's single-threaded event loop for its full duration,
     * stalling every other tenant's live rate-limiting/session traffic on a
     * shared instance; {@code SCAN} walks the same keyspace incrementally
     * without that blocking cost. Still only ever called from the once-a-day
     * job, never a request-handling path. Returns the number of counters
     * repaired.
     */
    public int repairAllMissingTtls() {
        try (Cursor<String> cursor = redisTemplate.scan(
            ScanOptions.scanOptions().match(MONTHLY_TOKEN_KEY_PREFIX + "*").count(500).build())) {
            int repaired = 0;
            while (cursor.hasNext()) {
                if (repairMissingTtl(cursor.next(), MONTHLY_WINDOW)) {
                    repaired++;
                }
            }
            return repaired;
        } catch (RuntimeException e) {
            log.debug("Hosted-pool token TTL repair sweep failed - leaving for the next lazy read to retry", e);
            return 0;
        }
    }

    private void incrementBy(String key, Duration window, long by) {
        incrementByAndGet(key, window, by);
    }

    /**
     * Same shape as {@link #incrementBy}, but returns the resulting
     * cumulative value ({@code null} on a Redis outage) so a caller can
     * compare it against a cap atomically - {@link
     * #checkAndReserveTokenBudget}'s reserve-then-compare step needs the
     * post-increment total; {@link #incrementBy}'s existing purely-additive
     * callers don't, so it stays a thin wrapper around this rather than
     * changing its own return type under existing callers.
     */
    private Long incrementByAndGet(String key, Duration window, long by) {
        // 2026-08-08 budget-reconciliation edge-case audit: must fail open
        // the same way PerUserBudgetService#incrementByAndGet does - EVERY
        // caller (checkAndReserveTokenBudget's reserve-then-compare AND
        // reconcileTokenReservation's release-on-error/cancel path, called
        // from AgentChatService's doOnError handler) treats a null return as
        // "Redis outage, nothing reserved/released" and fails open. Before
        // this fix a Redis exception propagated instead of returning null,
        // which for checkAndReserveTokenBudget meant a Redis blip HARD-FAILED
        // every hosted-pool turn instead of failing open like the rest of
        // this class, and for the release path meant a Redis exception thrown
        // from inside AgentChatService's doOnError callback risked masking
        // the original error signal delivered to the caller.
        try {
            Long count = redisTemplate.opsForValue().increment(key, by);
            if (count != null && count == by) {
                // First write to this window (count now equals exactly what we
                // just added) - set expiry once, on the write that created
                // the key.
                redisTemplate.expire(key, window);
            }
            return count;
        } catch (RuntimeException e) {
            log.debug("Hosted-pool token counter write failed for '{}' - failing open, same posture as every other write in this class", key, e);
            return null;
        }
    }
}
