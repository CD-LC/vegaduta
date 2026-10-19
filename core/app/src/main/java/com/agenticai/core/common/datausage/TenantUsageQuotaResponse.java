package com.agenticai.core.common.datausage;

/**
 * {@code GET /api/tenant/usage-quota}'s response - the signed-in tenant's
 * hosted-LLM-pool quota state (visible usage meter, item B3 of
 * docs/PRICING-MONETIZATION-AUDIT-2026-07-31.md §5.2). Field-for-field the
 * shape {@code web/app/usage/page.tsx} and the console's compact meter pill
 * consume.
 *
 * <p>{@code plan} is the {@link com.agenticai.core.security.TenantPlan} enum
 * name ({@code FREE}/{@code PREMIUM}/{@code UNLIMITED}). {@code
 * usesPlatformPool} is false for a paid tenant on BYOK (their own key/wallet
 * absorbs cost, so this window isn't enforced against their chats) - the
 * frontend hides the meter entirely in that case rather than showing a cap
 * that doesn't apply. {@code tokensMonthlyLimit} is the plan-scaled
 * effective value (FREE 1x, PREMIUM 2x, UNLIMITED 3x by default), exactly
 * what enforcement uses - see {@link
 * com.agenticai.core.agents.quota.FreeTierUsageLimiterService#quotaSnapshot}.
 *
 * <p>2026-09-26 simplification: the meter is a single rolling-30-day token
 * window - the message-count and daily-token windows were removed (see
 * {@code FreeTierUsageLimiterService}'s javadoc for why).
 *
 * <p>{@code limitEnforced} is false when billing is off ({@code app.edition.billing-enabled},
 * the personal profile): usage is still counted, but the ceiling never rejects a turn, so the
 * console shows what was used and no limit.
 */
public record TenantUsageQuotaResponse(
    String plan,
    boolean usesPlatformPool,
    long tokensUsedMonth,
    long tokensMonthlyLimit,
    boolean limitEnforced
) {}
