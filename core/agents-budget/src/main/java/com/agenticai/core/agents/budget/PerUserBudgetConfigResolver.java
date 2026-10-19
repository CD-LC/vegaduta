package com.agenticai.core.agents.budget;

import static com.agenticai.core.agents.budget.PerUserBudgetService.KEY_DAILY_USD;
import static com.agenticai.core.agents.budget.PerUserBudgetService.KEY_ENABLED;
import static com.agenticai.core.agents.budget.PerUserBudgetService.KEY_FALLBACK_USD_PER_1K;
import static com.agenticai.core.agents.budget.PerUserBudgetService.KEY_SPLIT_PCT_PREFIX;
import static com.agenticai.core.agents.budget.PerUserBudgetService.KEY_WINDOW_SECONDS;

import com.agenticai.core.agents.budget.PerUserBudgetService.BudgetConfig;
import com.agenticai.core.agents.budget.PerUserBudgetService.Modality;
import com.agenticai.core.security.TenantLimits;
import com.agenticai.core.security.TenantPlan;
import com.agenticai.core.tenancy.PlatformSettingsService;
import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Budget configuration for {@link PerUserBudgetService}: the {@code platform_setting} reads with
 * their defaults, the time-boxed snapshot cache, the per-plan tier and the admin write path. Split
 * out of the service to keep it readable; not a bean - one instance is owned by the service. Logs
 * under the service's logger so log routing is unchanged by the split.
 */
final class PerUserBudgetConfigResolver {

    private static final Logger log = LoggerFactory.getLogger(PerUserBudgetService.class);

    // Defaults - §4's "$0.15/user/day". Text/image/voice each get the full
    // budget as an independent sub-cap (100%); video is EXCLUDED from the
    // included budget (0% -> always blocked, pay-per-use per §4 "Video
    // generation: excluded... always pay-per-use").
    private static final String DEFAULT_ENABLED = "true";
    private static final String DEFAULT_DAILY_USD = "0.15";
    private static final int DEFAULT_WINDOW_SECONDS = 86_400;
    private static final Map<Modality, Integer> DEFAULT_SPLIT_PCT = new EnumMap<>(Modality.class);
    // ~$0.15 buys ~1.1M tokens on gemini-2.0-flash-lite (§4) => ~$0.136/MTok;
    // 0.00015 $/1k-tok = $0.15/MTok, a deliberately slightly-conservative
    // blended stand-in used ONLY when LlmPricingCatalog can't price the
    // hosted model that actually served the call.
    private static final String DEFAULT_FALLBACK_USD_PER_1K = "0.00015";

    static {
        DEFAULT_SPLIT_PCT.put(Modality.TEXT, 100);
        DEFAULT_SPLIT_PCT.put(Modality.IMAGE, 100);
        DEFAULT_SPLIT_PCT.put(Modality.VOICE, 100);
        DEFAULT_SPLIT_PCT.put(Modality.VIDEO, 0);
    }

    private final PlatformSettingsService platformSettingsService;
    private final long configCacheMs;
    private final TenantLimits tenantLimits;
    private final int premiumMultiplier;
    private final int unlimitedMultiplier;
    private final AtomicReference<CachedConfig> cachedConfig = new AtomicReference<>();

    PerUserBudgetConfigResolver(
        PlatformSettingsService platformSettingsService,
        long configCacheMs,
        TenantLimits tenantLimits,
        int premiumMultiplier,
        int unlimitedMultiplier
    ) {
        this.platformSettingsService = platformSettingsService;
        this.configCacheMs = configCacheMs;
        this.tenantLimits = tenantLimits;
        this.premiumMultiplier = premiumMultiplier;
        this.unlimitedMultiplier = unlimitedMultiplier;
    }

    /**
     * Admin write path - upserts each provided (non-null) field as its {@code
     * platform_setting} row, then busts the snapshot cache so the change is
     * live on the next turn without a redeploy. Nulls leave that field's row
     * untouched (partial update), mirroring {@code
     * PlatformLlmTaskService#upsert}'s optional-field posture.
     */
    BudgetConfig updateGlobalConfig(
        Boolean enabled,
        BigDecimal dailyUsd,
        Integer windowSeconds,
        Map<Modality, Integer> splitPct,
        BigDecimal fallbackUsdPer1kTokens,
        String updatedBy
    ) {
        if (enabled != null) {
            platformSettingsService.update(KEY_ENABLED, Boolean.toString(enabled), updatedBy);
        }
        if (dailyUsd != null) {
            platformSettingsService.update(KEY_DAILY_USD, dailyUsd.toPlainString(), updatedBy);
        }
        if (windowSeconds != null) {
            platformSettingsService.update(KEY_WINDOW_SECONDS, Integer.toString(windowSeconds), updatedBy);
        }
        if (splitPct != null) {
            splitPct.forEach((modality, pct) -> {
                if (pct != null) {
                    platformSettingsService.update(KEY_SPLIT_PCT_PREFIX + modality.name().toLowerCase(java.util.Locale.ROOT), Integer.toString(pct), updatedBy);
                }
            });
        }
        if (fallbackUsdPer1kTokens != null) {
            platformSettingsService.update(KEY_FALLBACK_USD_PER_1K, fallbackUsdPer1kTokens.toPlainString(), updatedBy);
        }
        cachedConfig.set(null);
        return loadGlobalConfig();
    }

    /**
     * Precedence seam (§4: per-user &rarr; per-tenant &rarr; per-plan &rarr;
     * global). No per-user/per-tenant override exists yet (still the natural
     * next increment, same as {@code LlmGatewayBudgetService}'s own "not
     * built speculatively" note) - this now implements the per-plan tier:
     * the tenant's {@link TenantPlan} scales the global {@code dailyUsd}
     * default via {@link #applyPlanTier}. A future per-user/per-tenant
     * override slots in ABOVE this (return early with the override before
     * falling through here) without any call site
     * ({@link PerUserBudgetService#checkBudget}/{@link PerUserBudgetService#recordSpend}) needing to change.
     */
    BudgetConfig resolveConfig(String tenantId, String userKey) {
        return resolveConfig(tenantId, userKey, null);
    }

    /** @param knownPlan see {@link PerUserBudgetService#checkBudget(String, String, Modality, TenantPlan)}'s javadoc; {@code null} to look it up here. */
    BudgetConfig resolveConfig(String tenantId, String userKey, TenantPlan knownPlan) {
        return applyPlanTier(effectiveGlobalConfig(), tenantId, knownPlan);
    }

    /**
     * Per-plan tier: scales the global daily cap by {@link #premiumMultiplier}/
     * {@link #unlimitedMultiplier} for PREMIUM/UNLIMITED tenants (FREE is the
     * baseline, multiplier 1 - identical semantics to {@code
     * FreeTierUsageLimiterService#multiplierFor}). Resolves the tenant's plan
     * via {@link TenantLimits#plan}, the same read-only accessor {@code
     * LlmGatewayBudgetService}/{@code FreeTierUsageLimiterService}'s callers
     * already use - no new lookup mechanism. A {@code null} tenantId or any
     * plan-lookup error fails OPEN to the unscaled global config (same
     * "a governance lookup hiccup must never break a chat turn" posture every
     * other fail-open path in this class already takes).
     */
    BudgetConfig applyPlanTier(BudgetConfig global, String tenantId, TenantPlan knownPlan) {
        if (tenantId == null) {
            return global;
        }
        int multiplier;
        try {
            // Dedupe fix (2026-08-06): reuse the caller's already-resolved plan
            // when it has one (see checkBudget's TenantPlan overload javadoc)
            // instead of re-querying tenantLimits.plan() here every time.
            TenantPlan plan = knownPlan != null ? knownPlan : tenantLimits.plan(tenantId);
            multiplier = switch (plan) {
                case UNLIMITED -> unlimitedMultiplier;
                case PREMIUM -> premiumMultiplier;
                case FREE -> 1;
            };
        } catch (RuntimeException e) {
            log.debug("Per-user budget plan lookup failed for tenant '{}' - failing open (no plan multiplier)", tenantId, e);
            return global;
        }
        if (multiplier <= 1) {
            return global;
        }
        return new BudgetConfig(
            global.enabled(),
            global.dailyUsd().multiply(BigDecimal.valueOf(multiplier)),
            global.windowSeconds(),
            global.splitPct(),
            global.fallbackUsdPer1kTokens()
        );
    }

    BudgetConfig effectiveGlobalConfig() {
        CachedConfig cached = cachedConfig.get();
        long now = System.currentTimeMillis();
        if (cached != null && (now - cached.loadedAtMs()) < configCacheMs) {
            return cached.config();
        }
        BudgetConfig fresh = loadGlobalConfig();
        cachedConfig.set(new CachedConfig(fresh, now));
        return fresh;
    }

    BudgetConfig loadGlobalConfig() {
        boolean enabled = Boolean.parseBoolean(platformSettingsService.getString(KEY_ENABLED, DEFAULT_ENABLED).trim());
        BigDecimal dailyUsd = parseUsd(platformSettingsService.getString(KEY_DAILY_USD, DEFAULT_DAILY_USD), DEFAULT_DAILY_USD);
        int windowSeconds = platformSettingsService.getInt(KEY_WINDOW_SECONDS, DEFAULT_WINDOW_SECONDS);
        Map<Modality, Integer> splitPct = new EnumMap<>(Modality.class);
        for (Modality modality : Modality.values()) {
            splitPct.put(modality, platformSettingsService.getInt(
                KEY_SPLIT_PCT_PREFIX + modality.name().toLowerCase(java.util.Locale.ROOT), DEFAULT_SPLIT_PCT.get(modality)));
        }
        BigDecimal fallback = parseUsd(
            platformSettingsService.getString(KEY_FALLBACK_USD_PER_1K, DEFAULT_FALLBACK_USD_PER_1K), DEFAULT_FALLBACK_USD_PER_1K);
        return new BudgetConfig(enabled, dailyUsd, windowSeconds, splitPct, fallback);
    }

    private static BigDecimal parseUsd(String value, String fallback) {
        try {
            return new BigDecimal(value.trim());
        } catch (NumberFormatException e) {
            log.warn("Per-user budget setting has a non-numeric value '{}' - using default {}", value, fallback);
            return new BigDecimal(fallback);
        }
    }

    private record CachedConfig(BudgetConfig config, long loadedAtMs) {
    }
}
