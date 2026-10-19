package com.agenticai.core.tenancy;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * The platform-wide "Smart AI" governor: a single master on/off switch plus
 * a dollar-denominated daily budget for the whole AI-native feature layer
 * (in-console copilot, AI-generated nudges/digests, error explanations,
 * etc.), each feature additionally capped on its own. Sibling of {@code
 * com.agenticai.core.agents.budget.PerUserBudgetService} - same fixed-window
 * Redis {@code INCRBY} + conditional-{@code EXPIRE} shape, same USD
 * micro-cents unit, same fail-open-on-Redis-error posture (a metering
 * outage must never break a feature; only the budget cap itself may).
 * Runtime-configurable via {@link PlatformSettingsService} rows seeded by
 * {@code V153__smart_ai_budget_settings.sql}, same short time-boxed
 * snapshot cache {@code PerUserBudgetService} uses so the master switch
 * stays off the hot path while still picking up an admin change within
 * the TTL; the admin write path ({@link #updateGlobalConfig}) busts the
 * cache immediately.
 *
 * <p>Lives in {@code core/tenancy} (next to {@link PlatformSettingsService},
 * not in {@code core/platform} where its admin REST endpoints are exposed)
 * deliberately: this module sits low enough in the dependency graph
 * ({@code tenancy} depends only on {@code admin}/{@code billing}/{@code
 * security}/{@code storage} - never on {@code chat}, {@code agents}, or
 * {@code platform}) that every future Smart AI feature - error explanations
 * in {@code core/chat}'s {@code ProviderErrorTranslator}, nudges or a
 * copilot in {@code core/agents}, anything in {@code core/platform} itself -
 * can depend on it directly without a cycle. It started out living in
 * {@code core/platform} (alongside the admin controller that exposes it),
 * which seemed natural until the first real consumer was scoped: {@code
 * core/platform} depends on {@code core/chat}, not the other way around, so
 * {@code ProviderErrorTranslator} could never have reached it there.
 *
 * <p>Deliberately returns a {@link BudgetDecision} rather than throwing -
 * every Smart AI feature's contract is "degrade gracefully, never surface
 * an error to the tenant" (a disabled or exhausted feature just falls
 * back to its non-AI behaviour), so there is no exception type here for
 * {@code GlobalExceptionHandler} to map.
 *
 * <h2>Atomic reservation (2026-09-28)</h2>
 * {@link #checkBudget} followed later by {@link #recordSpend} is a
 * check-then-act race: the check is a plain read of what PRIOR calls
 * recorded, the spend lands only after the LLM call returns (seconds later),
 * and nothing links the two. A burst of concurrent callers near a cap - the
 * capability-summary backfill fanning out over a catalog, or a provider
 * outage making every chat error ask for an explanation at once - all read
 * "under budget" and all spend, overshooting the cap by the burst size. Same
 * TOCTOU {@code PerUserBudgetService#checkAndReserveBudget} closed for
 * per-user dollars.
 *
 * <p>{@link #reserve} closes it the same way, on the same storage: an
 * {@code INCRBY} of the call's ESTIMATED cost into the feature window and
 * then the master window, each compared against its cap using the value that
 * {@code INCRBY} itself returned - Redis serialises the increments, so of N
 * concurrent reservations exactly the ones that fit are granted and the rest
 * see a total over the cap, undo their own increment and are refused. No Lua
 * needed: the counters are plain integers and the increment's return value is
 * the atomic read. Once the call finishes, {@link #settle} adds the signed
 * difference between the actual and the reserved cost (so the counter ends
 * up holding exactly the actual spend), and {@link #release} refunds a
 * reservation whose call never happened. The reservation is conservative by
 * construction - a concurrent caller can transiently see another's
 * reservation and be refused while that one is in flight - which is the
 * right side to err on for a governor whose only failure mode that matters
 * is overspend.
 *
 * <p>{@link #checkBudget}/{@link #recordSpend} keep their exact behaviour so
 * existing callers compile and run unchanged; they are the advisory,
 * racy pair and are documented as such. Callers should move to
 * reserve/settle.
 *
 * <p>Feature keys are plain strings, not a fixed enum - unlike {@code
 * PerUserBudgetService.Modality}'s closed set, the set of Smart AI
 * features is expected to grow over time, and each one should be usable
 * without a new migration or a new field on this class: an unconfigured
 * feature key simply falls back to {@link #KEY_DEFAULT_FEATURE_DAILY_USD}
 * / enabled=true.
 */
@Component
public class PlatformFeatureBudgetService {

    private static final Logger log = LoggerFactory.getLogger(PlatformFeatureBudgetService.class);

    private static final String SPEND_KEY_PREFIX = "agentic:smart-ai-budget:";
    private static final String MASTER_BUCKET = "__master__";
    static final long MICRO_CENTS_PER_USD_LITERAL = 100_000_000L;
    private static final BigDecimal MICRO_CENTS_PER_USD = BigDecimal.valueOf(MICRO_CENTS_PER_USD_LITERAL);

    // PlatformSetting keys - also the rows V151 seeds (master ones) / the
    // per-feature key shape (not seeded, see that migration's own comment).
    public static final String KEY_ENABLED = "platform.smart-ai.enabled";
    public static final String KEY_WINDOW_SECONDS = "platform.smart-ai.budget.window-seconds";
    public static final String KEY_MASTER_DAILY_USD = "platform.smart-ai.budget.master-daily-usd";
    public static final String KEY_DEFAULT_FEATURE_DAILY_USD = "platform.smart-ai.budget.default-feature-daily-usd";
    private static final String KEY_FEATURE_PREFIX = "platform.smart-ai.feature.";
    private static final String KEY_FEATURE_ENABLED_SUFFIX = ".enabled";
    private static final String KEY_FEATURE_DAILY_USD_SUFFIX = ".daily-usd";

    private static final String DEFAULT_ENABLED = "true";
    private static final int DEFAULT_WINDOW_SECONDS = 86_400;
    private static final String DEFAULT_MASTER_DAILY_USD = "2.00";
    private static final String DEFAULT_FEATURE_DAILY_USD = "0.25";

    private final StringRedisTemplate redisTemplate;
    private final PlatformSettingsService platformSettingsService;
    private final long configCacheMs;
    private final AtomicReference<CachedGlobalConfig> cachedGlobalConfig = new AtomicReference<>();

    public PlatformFeatureBudgetService(
        StringRedisTemplate redisTemplate,
        PlatformSettingsService platformSettingsService,
        @Value("${app.smart-ai-budget.config-cache-ms:10000}") long configCacheMs
    ) {
        this.redisTemplate = redisTemplate;
        this.platformSettingsService = platformSettingsService;
        this.configCacheMs = configCacheMs;
    }

    /** True when the master Smart AI switch is on. Every feature check below implies this. */
    public boolean isEnabled() {
        return effectiveGlobalConfig().enabled();
    }

    /**
     * ADVISORY pre-call gate - prefer {@link #reserve}. This is a plain read
     * of spend already recorded by earlier calls, so concurrent callers near a
     * cap can all pass it before any of them records (see the class javadoc's
     * "Atomic reservation" section). Kept, unchanged, for existing callers.
     *
     * <p>Never throws - callers branch on {@link
     * BudgetDecision#allowed()} and fall back to their non-AI behaviour when
     * it is {@code false}; that fallback IS the feature's correct behaviour
     * here, not an error path. Checks, in order: the master switch, the
     * feature's own switch, the feature's own daily cap, then the shared
     * master daily cap - the first blocking gate wins and is reported in
     * {@link BudgetDecision#reason()} (useful for logs/metrics, not meant
     * for tenant display).
     *
     * <p>Fail-open on any Redis error, identical posture to {@code
     * PerUserBudgetService#checkBudget}: a metering outage must never be
     * the reason a Smart AI feature silently disables itself.
     */
    public BudgetDecision checkBudget(String featureKey) {
        GlobalConfig config = effectiveGlobalConfig();
        if (!config.enabled()) {
            return BudgetDecision.blocked("smart-ai-disabled");
        }
        FeatureConfig featureConfig = loadFeatureConfig(featureKey, config);
        if (!featureConfig.enabled()) {
            return BudgetDecision.blocked("feature-disabled");
        }
        long featureSpent = currentSpendMicroCents(featureBucketKey(featureKey));
        if (featureSpent >= featureConfig.dailyCapMicroCents()) {
            return BudgetDecision.blocked("feature-budget-exhausted");
        }
        long masterSpent = currentSpendMicroCents(masterBucketKey());
        if (masterSpent >= config.masterDailyCapMicroCents()) {
            return BudgetDecision.blocked("master-budget-exhausted");
        }
        return BudgetDecision.granted();
    }

    /**
     * Post-call spend record for the ADVISORY {@link #checkBudget} path - use
     * {@link #settle} after a {@link #reserve} instead (calling this after a
     * reserve would count the call twice). Adds {@code costUsd} to BOTH this feature's
     * own window and the shared master window (a feature still under its
     * own cap can still be stopped by the master cap on the next call).
     * Never throws, same fail-open posture as {@code
     * PerUserBudgetService#recordSpend}. A null/non-positive cost, or one
     * that rounds below one micro-cent, is a no-op.
     */
    public void recordSpend(String featureKey, BigDecimal costUsd) {
        if (featureKey == null || costUsd == null || costUsd.signum() <= 0) {
            return;
        }
        long microCents = toMicroCents(costUsd);
        if (microCents <= 0) {
            return;
        }
        Duration window = Duration.ofSeconds(effectiveGlobalConfig().windowSeconds());
        incrementByAndGet(featureBucketKey(featureKey), window, microCents);
        incrementByAndGet(masterBucketKey(), window, microCents);
    }

    /** USD to micro-cents, HALF_UP; null/non-positive is 0. */
    private static long toMicroCents(BigDecimal usd) {
        if (usd == null || usd.signum() <= 0) {
            return 0L;
        }
        return usd.multiply(MICRO_CENTS_PER_USD).setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    /**
     * Atomic pre-call gate: reserves {@code estimatedCostUsd} against BOTH this
     * feature's window and the shared master window before the call is made,
     * and refuses (reserving nothing) if either would exceed its cap. Gates are
     * checked in the same order and reported with the same reasons as {@link
     * #checkBudget}. Never throws.
     *
     * <p>A null, non-positive or sub-micro-cent estimate reserves ONE
     * micro-cent - the smallest amount that still makes the compare
     * meaningful, i.e. "refuse once the window is at or over the cap", which is
     * exactly {@link #checkBudget}'s semantics made atomic. Callers that can
     * estimate should: a realistic estimate is what stops a burst overshooting.
     *
     * <p>Fail-open on Redis errors, same posture as the rest of the class: a
     * window whose increment could not be performed is simply not reserved
     * (the returned reservation records that per window), and {@link #settle}
     * then records the full actual cost there instead of a delta.
     *
     * <p>Every GRANTED reservation must be passed to exactly one of {@link
     * #settle} (the call happened - including when its output turned out
     * unusable) or {@link #release} (the call never reached the model).
     * Forgetting to is a leak of the estimate for the rest of the window, not
     * an overspend. A second settle/release of the same reservation is a no-op.
     */
    public Reservation reserve(String featureKey, BigDecimal estimatedCostUsd) {
        GlobalConfig config = effectiveGlobalConfig();
        if (!config.enabled()) {
            return Reservation.blocked(featureKey, "smart-ai-disabled");
        }
        FeatureConfig featureConfig = loadFeatureConfig(featureKey, config);
        if (!featureConfig.enabled()) {
            return Reservation.blocked(featureKey, "feature-disabled");
        }
        long estimate = Math.max(1L, toMicroCents(estimatedCostUsd));
        Duration window = Duration.ofSeconds(config.windowSeconds());
        String featureBucket = featureBucketKey(featureKey);
        String masterBucket = masterBucketKey();

        Long featureTotal = incrementByAndGet(featureBucket, window, estimate);
        if (featureTotal != null && featureTotal > featureConfig.dailyCapMicroCents()) {
            incrementByAndGet(featureBucket, window, -estimate);
            return Reservation.blocked(featureKey, "feature-budget-exhausted");
        }
        long featureReserved = featureTotal == null ? 0L : estimate;

        Long masterTotal = incrementByAndGet(masterBucket, window, estimate);
        if (masterTotal != null && masterTotal > config.masterDailyCapMicroCents()) {
            incrementByAndGet(masterBucket, window, -estimate);
            if (featureReserved > 0) {
                incrementByAndGet(featureBucket, window, -featureReserved);
            }
            return Reservation.blocked(featureKey, "master-budget-exhausted");
        }
        long masterReserved = masterTotal == null ? 0L : estimate;
        return new Reservation(featureKey, true, null, featureReserved, masterReserved);
    }

    /**
     * Post-call reconciliation for a {@link #reserve} grant: converts the
     * reservation into the call's ACTUAL cost by adding {@code actual -
     * reserved} to each window (a negative delta refunds an over-estimate).
     * A null or non-positive {@code actualCostUsd} settles at zero, i.e. a
     * full refund. No-op for a refused reservation or one already settled or
     * released. Never throws.
     *
     * <p>A window that rolled over between reserve and settle gets the delta
     * as its first write (and so its expiry) - at worst a new window starts a
     * few micro-cents negative or positive by one call's estimate error, never
     * a counter with no TTL.
     */
    public void settle(Reservation reservation, BigDecimal actualCostUsd) {
        if (reservation == null || !reservation.allowed() || !reservation.markSettled()) {
            return;
        }
        long actual = Math.max(0L, toMicroCents(actualCostUsd));
        Duration window = Duration.ofSeconds(effectiveGlobalConfig().windowSeconds());
        long featureDelta = actual - reservation.featureReservedMicroCents();
        if (featureDelta != 0) {
            incrementByAndGet(featureBucketKey(reservation.featureKey()), window, featureDelta);
        }
        long masterDelta = actual - reservation.masterReservedMicroCents();
        if (masterDelta != 0) {
            incrementByAndGet(masterBucketKey(), window, masterDelta);
        }
    }

    /** Refunds a {@link #reserve} grant whose call never happened - {@link #settle} at zero cost. */
    public void release(Reservation reservation) {
        settle(reservation, BigDecimal.ZERO);
    }

    /**
     * Handle returned by {@link #reserve}. {@link #allowed()}/{@link #reason()}
     * mirror {@link BudgetDecision}; the reserved amounts are per window
     * because either increment can independently fail open. Settles at most
     * once (see {@link #settle}).
     */
    public static final class Reservation {
        private final String featureKey;
        private final boolean allowed;
        private final String reason;
        private final long featureReservedMicroCents;
        private final long masterReservedMicroCents;
        private final java.util.concurrent.atomic.AtomicBoolean settled = new java.util.concurrent.atomic.AtomicBoolean();

        Reservation(String featureKey, boolean allowed, String reason, long featureReservedMicroCents, long masterReservedMicroCents) {
            this.featureKey = featureKey;
            this.allowed = allowed;
            this.reason = reason;
            this.featureReservedMicroCents = featureReservedMicroCents;
            this.masterReservedMicroCents = masterReservedMicroCents;
        }

        static Reservation blocked(String featureKey, String reason) {
            return new Reservation(featureKey, false, reason, 0L, 0L);
        }

        public String featureKey() {
            return featureKey;
        }

        public boolean allowed() {
            return allowed;
        }

        public String reason() {
            return reason;
        }

        /** The same outcome as a {@link BudgetDecision}, for callers that log/branch on one. */
        public BudgetDecision decision() {
            return new BudgetDecision(allowed, reason);
        }

        long featureReservedMicroCents() {
            return featureReservedMicroCents;
        }

        long masterReservedMicroCents() {
            return masterReservedMicroCents;
        }

        private boolean markSettled() {
            return settled.compareAndSet(false, true);
        }
    }

    /** Outcome of {@link #checkBudget} - never an exception, see class javadoc. */
    public record BudgetDecision(boolean allowed, String reason) {
        static BudgetDecision granted() {
            return new BudgetDecision(true, null);
        }

        static BudgetDecision blocked(String reason) {
            return new BudgetDecision(false, reason);
        }
    }

    // ---- config read/write (admin surface) ---------------------------------

    /** Current effective global config - what the admin GET endpoint returns. */
    public GlobalConfig currentGlobalConfig() {
        return effectiveGlobalConfig();
    }

    /**
     * Admin write path - upserts each provided (non-null) field as its
     * {@code platform_setting} row, then busts the snapshot cache so the
     * change is live on the next call without a redeploy. Nulls leave that
     * field's row untouched (partial update), mirroring {@code
     * PerUserBudgetService#updateGlobalConfig}'s posture.
     */
    public GlobalConfig updateGlobalConfig(
        Boolean enabled, BigDecimal masterDailyUsd, Integer windowSeconds,
        BigDecimal defaultFeatureDailyUsd, String updatedBy
    ) {
        if (enabled != null) {
            platformSettingsService.update(KEY_ENABLED, Boolean.toString(enabled), updatedBy);
        }
        if (masterDailyUsd != null) {
            platformSettingsService.update(KEY_MASTER_DAILY_USD, masterDailyUsd.toPlainString(), updatedBy);
        }
        if (windowSeconds != null) {
            platformSettingsService.update(KEY_WINDOW_SECONDS, Integer.toString(windowSeconds), updatedBy);
        }
        if (defaultFeatureDailyUsd != null) {
            platformSettingsService.update(KEY_DEFAULT_FEATURE_DAILY_USD, defaultFeatureDailyUsd.toPlainString(), updatedBy);
        }
        cachedGlobalConfig.set(null);
        return loadGlobalConfig();
    }

    /** Current effective config for one feature - what the admin GET .../features/{key} endpoint returns. */
    public FeatureConfig currentFeatureConfig(String featureKey) {
        return loadFeatureConfig(featureKey, effectiveGlobalConfig());
    }

    /** Admin write path for one feature's own switch/cap. Same partial-update posture as {@link #updateGlobalConfig}. */
    public FeatureConfig updateFeatureConfig(String featureKey, Boolean enabled, BigDecimal dailyUsd, String updatedBy) {
        if (enabled != null) {
            platformSettingsService.update(featureEnabledKey(featureKey), Boolean.toString(enabled), updatedBy);
        }
        if (dailyUsd != null) {
            platformSettingsService.update(featureDailyUsdKey(featureKey), dailyUsd.toPlainString(), updatedBy);
        }
        return currentFeatureConfig(featureKey);
    }

    // ---- config load -----------------------------------------------------

    private GlobalConfig effectiveGlobalConfig() {
        CachedGlobalConfig cached = cachedGlobalConfig.get();
        long now = System.currentTimeMillis();
        if (cached != null && (now - cached.loadedAtMs()) < configCacheMs) {
            return cached.config();
        }
        GlobalConfig fresh = loadGlobalConfig();
        cachedGlobalConfig.set(new CachedGlobalConfig(fresh, now));
        return fresh;
    }

    private GlobalConfig loadGlobalConfig() {
        boolean enabled = Boolean.parseBoolean(platformSettingsService.getString(KEY_ENABLED, DEFAULT_ENABLED).trim());
        int windowSeconds = platformSettingsService.getInt(KEY_WINDOW_SECONDS, DEFAULT_WINDOW_SECONDS);
        BigDecimal masterDailyUsd = parseUsd(
            platformSettingsService.getString(KEY_MASTER_DAILY_USD, DEFAULT_MASTER_DAILY_USD), DEFAULT_MASTER_DAILY_USD);
        BigDecimal defaultFeatureDailyUsd = parseUsd(
            platformSettingsService.getString(KEY_DEFAULT_FEATURE_DAILY_USD, DEFAULT_FEATURE_DAILY_USD), DEFAULT_FEATURE_DAILY_USD);
        return new GlobalConfig(enabled, windowSeconds, masterDailyUsd, defaultFeatureDailyUsd);
    }

    private FeatureConfig loadFeatureConfig(String featureKey, GlobalConfig global) {
        boolean enabled = platformSettingsService.getBoolean(featureEnabledKey(featureKey), true);
        String defaultDailyUsd = global.defaultFeatureDailyUsd().toPlainString();
        BigDecimal dailyUsd = parseUsd(platformSettingsService.getString(featureDailyUsdKey(featureKey), defaultDailyUsd), defaultDailyUsd);
        return new FeatureConfig(featureKey, enabled, dailyUsd);
    }

    private static BigDecimal parseUsd(String value, String fallback) {
        try {
            return new BigDecimal(value.trim());
        } catch (NumberFormatException e) {
            log.warn("Smart AI budget setting has a non-numeric value '{}' - using default {}", value, fallback);
            return new BigDecimal(fallback);
        }
    }

    // ---- Redis plumbing (mirrors PerUserBudgetService) --------------------

    private long currentSpendMicroCents(String key) {
        try {
            String value = redisTemplate.opsForValue().get(key);
            return value == null ? 0L : Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            // Corrupt value - fail open (treat as unspent), same posture as a
            // Redis outage: never block a feature on a metering-store hiccup.
            return 0L;
        } catch (RuntimeException e) {
            log.debug("Smart AI budget read failed - failing open", e);
            return 0L;
        }
    }

    /**
     * {@code INCRBY} returning the post-increment total ({@code null} on a
     * Redis error - fail open). The returned total is what makes {@link
     * #reserve} atomic: Redis serialises increments, so the value each caller
     * gets back already includes every reservation that beat it. {@code by}
     * may be negative (undo / refund).
     */
    private Long incrementByAndGet(String key, Duration window, long by) {
        try {
            Long count = redisTemplate.opsForValue().increment(key, by);
            if (count != null && count == by) {
                // First write to this window - set expiry once, same idiom
                // as PerUserBudgetService#incrementBy.
                redisTemplate.expire(key, window);
            }
            return count;
        } catch (RuntimeException e) {
            log.debug("Smart AI budget write failed - failing open", e);
            return null;
        }
    }

    private static String featureBucketKey(String featureKey) {
        return SPEND_KEY_PREFIX + "feature:" + featureKey;
    }

    private static String masterBucketKey() {
        return SPEND_KEY_PREFIX + MASTER_BUCKET;
    }

    private static String featureEnabledKey(String featureKey) {
        return KEY_FEATURE_PREFIX + featureKey + KEY_FEATURE_ENABLED_SUFFIX;
    }

    private static String featureDailyUsdKey(String featureKey) {
        return KEY_FEATURE_PREFIX + featureKey + KEY_FEATURE_DAILY_USD_SUFFIX;
    }

    private record CachedGlobalConfig(GlobalConfig config, long loadedAtMs) {
    }

    /** Effective global Smart AI config - what the admin GET endpoint returns. */
    public record GlobalConfig(boolean enabled, int windowSeconds, BigDecimal masterDailyUsd, BigDecimal defaultFeatureDailyUsd) {
        long masterDailyCapMicroCents() {
            return masterDailyUsd.multiply(MICRO_CENTS_PER_USD).setScale(0, RoundingMode.HALF_UP).longValueExact();
        }
    }

    /** Effective per-feature Smart AI config - what the admin GET .../features/{key} endpoint returns. */
    public record FeatureConfig(String featureKey, boolean enabled, BigDecimal dailyUsd) {
        long dailyCapMicroCents() {
            return dailyUsd.multiply(MICRO_CENTS_PER_USD).setScale(0, RoundingMode.HALF_UP).longValueExact();
        }
    }
}
