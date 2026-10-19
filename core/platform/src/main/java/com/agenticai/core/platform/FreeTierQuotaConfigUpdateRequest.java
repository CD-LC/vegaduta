package com.agenticai.core.platform;

/**
 * Body for {@code PUT /api/platform/free-tier-quota} - the admin-tunable
 * counterpart to {@link PerUserBudgetConfigUpdateRequest}, but for the
 * platform-hosted-pool TOKEN quota ({@code FreeTierUsageLimiterService})
 * rather than the per-user dollar budget. Every field is optional/nullable -
 * a null field leaves that setting's stored row untouched (partial update),
 * same posture as {@link PerUserBudgetConfigUpdateRequest}.
 *
 * <p>2026-09-26: the quota collapsed to a single rolling-30-day token window
 * (message-count and daily-token windows removed - see {@code
 * FreeTierUsageLimiterService}'s javadoc), so {@code dailyTokenLimit} is
 * gone from this contract. Raising {@code unlimitedMultiplier} (or {@code
 * monthlyTokenLimit} directly) here is the correct place to raise the
 * "Unlimited plan monthly token limit reached" ceiling - the per-user
 * dollar budget on the Budgets screen is a completely independent gate and
 * does not affect this one.
 *
 * @param monthlyTokenLimit   base FREE-tier rolling-30-day token cap; PREMIUM/UNLIMITED multiply this.
 * @param premiumMultiplier   multiplier applied to the limit above for PREMIUM-plan tenants.
 * @param unlimitedMultiplier multiplier applied to the limit above for UNLIMITED-plan tenants
 *                            (the one behind the default 400,000 &times; 3 = 1,200,000 tokens/30-day cap).
 */
public record FreeTierQuotaConfigUpdateRequest(
    Long monthlyTokenLimit,
    Integer premiumMultiplier,
    Integer unlimitedMultiplier
) {
}
