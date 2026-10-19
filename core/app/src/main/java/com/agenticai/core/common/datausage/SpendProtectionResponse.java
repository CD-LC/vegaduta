package com.agenticai.core.common.datausage;

import java.time.Instant;
import java.util.List;

/**
 * {@code GET /api/tenant/spend-protection}'s response - the tenant-facing
 * "what's protecting your spend" transparency panel (additive to the
 * existing usage meter, {@link TenantUsageQuotaResponse}). Reports the real,
 * currently-known state of every cost-optimization mechanism this platform
 * actually runs, strictly evidence-based:
 *
 * <ul>
 *   <li>{@link #caching()} - which providers have prompt caching active,
 *       enumerated from {@code core.chat.ProviderCachingRegistry} (never a
 *       hardcoded provider list - see that class's javadoc), plus a REAL
 *       cache-token-proportion metric derived from {@code LlmCallLog}'s
 *       {@code cache_creation_tokens}/{@code cache_read_tokens} columns
 *       (V195 migration) over {@link #cacheWindowDays()} - the one mechanism
 *       here with real per-call data to quantify. Metrics are {@code null}
 *       (never a fabricated 0) for a provider with zero calls in the
 *       window.</li>
 *   <li>{@link #costAwareRouting()}, {@link #toolSchemaMinimization()},
 *       {@link #approvalGates()} - active/inactive plus a plain-language
 *       description, deliberately WITHOUT any invented dollar or percentage
 *       savings figure: no per-call data exists today that isolates what
 *       these three specifically saved versus the counterfactual of them
 *       being off.</li>
 * </ul>
 */
public record SpendProtectionResponse(
    Instant generatedAt,
    int cacheWindowDays,
    List<CachingProviderStatus> caching,
    CostMechanismStatus costAwareRouting,
    CostMechanismStatus toolSchemaMinimization,
    CostMechanismStatus approvalGates
) {

    /**
     * One provider's caching state. {@code active} comes straight from
     * {@code ProviderCachingRegistry#activeProviders()}. The two metrics are
     * {@code null} when this tenant had zero calls to that provider in the
     * window ("no data yet"), never a fabricated zero:
     *
     * <p>{@code cacheReadTokenShare} - of this provider's total input tokens
     * in the window (plain prompt tokens + cache-write tokens + cache-read
     * tokens - Anthropic's cache tokens are NOT folded into plain prompt
     * tokens, see {@code LlmMetricsAdvisor}), the fraction actually served
     * from cache rather than billed at full input price.
     *
     * <p>{@code callsWithCacheHitShare} - of this provider's calls in the
     * window, the fraction that read at least one token from cache.
     */
    public record CachingProviderStatus(
        String provider,
        boolean active,
        long callsInWindow,
        Double cacheReadTokenShare,
        Double callsWithCacheHitShare
    ) {}

    /** One non-caching mechanism's active/inactive state plus a plain-language description - no quantified savings unless {@code detail} states one derived from real logged data. */
    public record CostMechanismStatus(String id, boolean active, String description, String detail) {}
}
