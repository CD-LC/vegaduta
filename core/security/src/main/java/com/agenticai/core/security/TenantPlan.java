package com.agenticai.core.security;

/**
 * A tenant's subscription tier - {@code FREE} (platform-hosted open-source
 * models only, no BYOK, no WhatsApp - see {@link
 * com.agenticai.core.providers.PlatformHostedLlmProvider} in {@code
 * core/providers}), {@code PREMIUM} (BYOK premium providers unlocked, higher
 * caps, WhatsApp), or {@code UNLIMITED} (no caps, white-label option). See
 * Agentic-AI-Platform-Subscription-Plan-and-GTM-Strategy.md for the full
 * tier matrix this enum backs.
 *
 * <p><b>Moved here from {@code core.tenancy}, in place, same values renamed</b>
 * ("implement the free tier / plug in Groq and other providers, on run
 * time... implement the best to control the eco system"). This field existed
 * on {@code Tenant} since Phase 0 Stage 1 as {@code FREE_SELF_HOSTED,
 * STARTER, BUSINESS, ENTERPRISE} but was never actually read anywhere to gate
 * anything - the "universal tier" product decision (see {@link
 * AccountType}'s javadoc) flattened all enforcement to the same numbers for
 * every tenant regardless of this field's value, and every signup was
 * unconditionally recorded as {@code STARTER}. Repurposed now that a real
 * Free/Premium/Unlimited product exists for it to describe, rather than
 * adding a second, competing "which plan is this tenant on" field.
 *
 * <p>Lives in {@code core.security} - not {@code core.tenancy}, which
 * actually owns the {@code Tenant} row this is stored on - for the exact
 * same module-cycle-avoidance reason {@link AccountType} already documents:
 * {@code core.agents} (which needs to read a tenant's plan to route chat
 * calls - see {@code AgentChatService}) cannot depend on {@code
 * core.tenancy} without creating a cycle (tenancy depends on chat, which
 * agents also depends on), but both already depend on {@code security}. See
 * {@link TenantLimits#plan(String)} for the read path.
 */
public enum TenantPlan {
    FREE,
    PREMIUM,
    UNLIMITED
}
