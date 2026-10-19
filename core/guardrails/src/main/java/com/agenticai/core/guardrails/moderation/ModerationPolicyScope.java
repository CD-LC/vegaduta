package com.agenticai.core.guardrails.moderation;

/**
 * The three levels at which a policy pack can be switched on or off. Replaces
 * the retired platform-side {@code ModerationScopeType} (PLAN/TENANT only),
 * and adds the level that enum's backing table structurally could not express:
 * {@link #GLOBAL}. {@link ModerationPolicyService} resolves enablement
 * most-specific-wins - a present TENANT row beats a present PLAN row beats the
 * GLOBAL default, where "present" includes an explicit {@code false} - so
 * GLOBAL is a real, addressable scope: an operator can enable a pack
 * platform-wide and then opt ONE tenant back out.
 *
 * <p>Each constant carries its {@code platform_setting} key-suffix fragment so
 * the key format lives in exactly one place - two services independently
 * re-deriving the key scheme (one writing, one reading) is precisely the
 * write-config-nobody-reads defect the 2026-08 moderation unification removed.
 */
public enum ModerationPolicyScope {

    /** The platform-wide pack default. Needs no scope key. */
    GLOBAL(".enabled"),

    /**
     * Every tenant on a plan. The scope key is the plan token as it reaches
     * the runtime resolver from {@code TenantPlan.name()} - i.e. upper-case
     * ({@code FREE}/{@code PREMIUM}/...); {@link ModerationPolicyService}'s
     * write path normalises case so a stored key can never miss at read time.
     */
    PLAN(".enabled.plan."),

    /** One tenant, matched by tenant id - the most specific level. */
    TENANT(".enabled.tenant.");

    private final String suffix;

    ModerationPolicyScope(String suffix) {
        this.suffix = suffix;
    }

    /**
     * The full {@code platform_setting} key for {@code pack}'s enablement at
     * this scope. {@code scopeKey} (plan token or tenant id) is required for
     * PLAN/TENANT and ignored for GLOBAL. Package-private: only {@link
     * ModerationPolicyService} composes keys - callers go through it.
     */
    String enabledKey(ModerationPolicyPack pack, String scopeKey) {
        String base = "moderation.pack." + pack.configKey() + suffix;
        return this == GLOBAL ? base : base + scopeKey;
    }
}
