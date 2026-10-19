package com.agenticai.core.security;

import java.util.Optional;

/**
 * Read-only view of a tenant's plan limits, keyed by {@code tenantId} (the
 * JWT {@code tenant_id} claim / Postgres schema name - the same string
 * {@link TenantContext#getTenantId()} carries, not the tenant's UUID
 * primary key).
 *
 * <p>Lives here, in {@code security}, rather than in {@code tenancy} (which
 * actually owns the {@code Tenant} row this is computed from) specifically
 * to avoid a module cycle: {@code tenancy} already depends on {@code admin}
 * for Keycloak provisioning, and {@code admin}'s {@code UserAdminController}
 * is the other caller that needs these limits to enforce the B2B/B2C/Family
 * user-seat cap. Both {@code admin} and {@code agents} already depend on
 * {@code security}, so they can call this interface without depending on
 * {@code tenancy} directly; {@code tenancy} itself supplies the one Spring
 * bean that implements it. Same dependency-inversion trick this codebase
 * already documents for cross-module plumbing.
 */
public interface TenantLimits {

    /** Hard cap on team members (Keycloak users) this tenant may have - always present, every plan is bounded. */
    int maxUsers(String tenantId);

    /**
     * Hard cap on agents this tenant may have at any one time, or empty for
     * "no plan-level cap" (today: every plan except B2B). Enforced as a
     * standing ceiling, not just at creation - deleting an agent always
     * re-opens a slot under the same cap.
     */
    Optional<Integer> maxAgents(String tenantId);

    /**
     * Hard cap on workflows this tenant may have saved at any one time, or
     * empty for "no plan-level cap." Same "standing ceiling, not just at
     * creation" posture as {@link #maxAgents}: deleting a workflow always
     * re-opens a slot under the same cap.
     */
    Optional<Integer> maxWorkflows(String tenantId);

    /**
     * True for the one shared /try sandbox schema. It serves every anonymous
     * visitor at once, so per-schema plan gates (agent/workflow/KB counts and
     * FREE's "catalog-only MCP connectors") step aside there; the Agent
     * Studio applies per-session and per-IP caps of its own. Failure-safe:
     * anything unknown is "not demo", i.e. the gates stay.
     */
    default boolean isSharedDemoTenant(String tenantId) {
        return false;
    }

    AccountType accountType(String tenantId);

    ExperienceMode experienceMode(String tenantId);

    /**
     * This tenant's subscription tier - see {@link TenantPlan}. Added
     * alongside the platform-hosted-LLM free tier build so {@code
     * core.agents}' {@code AgentChatService} can route a {@code FREE}-plan
     * tenant's chat calls through the hosted pool instead of BYOK, without
     * depending on {@code core.tenancy} directly - same cross-module read
     * this interface already exists for (see this interface's own javadoc).
     */
    TenantPlan plan(String tenantId);

    /**
     * The plan actually written on the {@code Tenant} row, ignoring platform
     * free mode. {@link #plan} is the right read for almost every gate: in
     * free mode it answers {@code UNLIMITED} for everyone, which is the point
     * of free mode. This one is for the few features whose spend the platform
     * must NOT open to every tenant just because free mode is on - today the
     * Agent Lab's knockout and consistency runs, which
     * fan one click out into up to 120 hosted-pool model calls. An active
     * complimentary grant IS written onto the row ({@code Tenant
     * #grantComplimentaryPlan}), so a fresh signup still reads {@code
     * UNLIMITED} here for its three-month window and drops to {@code FREE}
     * with the grant. Defaults to {@link #plan} so that a fake that never
     * heard of free mode keeps working.
     */
    default TenantPlan storedPlan(String tenantId) {
        return plan(tenantId);
    }

    /**
     * Direct feedback (issue report): "every non free tenant should get an
     * option to select use platform llm" - a PREMIUM/UNLIMITED tenant's own
     * opt-in to route chat calls through the platform-hosted pool instead of
     * BYOK (same pool FREE tenants always use), toggled via {@code PUT
     * /api/tenant/settings}. Always {@code false} for a FREE tenant in
     * practice (irrelevant for them - they route through the hosted pool
     * unconditionally regardless of this flag, see {@code AgentChatService
     * #buildChatClientFor}), but the flag itself is plan-agnostic storage on
     * {@code Tenant}, not computed from plan.
     */
    boolean usePlatformLlm(String tenantId);

    /**
     * <b>The</b> hosted-pool routing decision: do this tenant's LLM calls run
     * on the platform's own pooled models rather than on the tenant's own BYOK
     * credential? Every caller that used to spell out {@code plan ==
     * TenantPlan.FREE || tenantLimits.usePlatformLlm(tenantId)} inline asks
     * this instead - {@code AgentChatService}, {@code AgentService}, {@code
     * AppBuilderCompletionService}, {@code GenerationParamsInferenceService},
     * {@code RlModelRouteArmValidator}, {@code TenantUsageQuotaController},
     * {@code ProviderService} and {@code WorkflowExecutionParamsInferenceService}.
     * That expression had been copy-pasted to nine sites, which is how the bug
     * below survived: fixing it in chat alone would have left the same tenant
     * hosted in chat but BYOK in agent creation, the app builder, provider
     * availability, workflow inference, quota reporting and RL arm validation.
     *
     * <p><b>Why this is more than {@code FREE || opted-in}.</b> A brand-new
     * self-service/social signup is not left on {@code FREE}: {@code
     * TenantProvisioningRecorder#activate} grants it a complimentary
     * {@code UNLIMITED} plan for three months, in the same transaction as the
     * signup. {@code Tenant#grantComplimentaryPlan} writes only the plan and
     * the comp-grant bookkeeping - it does not touch {@link #usePlatformLlm},
     * whose column defaults {@code false} and whose only non-test writer is the
     * tenant-admin toggle in {@code TenantSettingsController}. So under the old
     * two-term rule the grant made {@code plan == FREE} false while the opt-in
     * was still false, and the tenant fell off the hosted pool for exactly the
     * three-month window in which every first-run surface promises "a hosted
     * model is already included - no API key needed". Three months later
     * {@code CompGrantExpiryReverter} dropped them to FREE and they silently
     * GAINED the pool. The complimentary grant is the only window in a tenant's
     * life where that inversion happened; treating an active grant as hosted
     * closes it.
     *
     * <p>Deliberately computed, not stored. The alternative - writing {@code
     * usePlatformLlm = true} when the grant is made - would persist state that
     * the expiry revert then has to unwind, and an unwind cannot tell a
     * grant-written {@code true} from a tenant who turned the toggle on
     * themselves. The maintainer chose the computed form for exactly that reason.
     * {@link #usePlatformLlm} therefore stays what it has always been: raw
     * storage for the tenant's own opt-in, which is what {@code
     * TenantSettingsController} renders and writes and what {@code
     * TenantBrandingLogoController} passes through as a DTO field.
     *
     * <p>An EXPIRED grant does not count, and this compares against the clock
     * rather than trusting {@code CompGrantExpiryReverter} to have already run
     * and nulled the column. Nothing else changes: a {@code FREE} tenant is
     * hosted as before, a tenant that flipped the toggle is hosted as before,
     * and a genuinely PAID {@code PREMIUM}/{@code UNLIMITED} tenant with no
     * live grant and no toggle stays BYOK as before.
     *
     * @param tenantId schema name; {@code null} (no request context) is BYOK/
     *     unrestricted, matching the guard the inline call sites already used.
     * @param plan this tenant's already-resolved plan for the current call, or
     *     {@code null} to have the implementation resolve it. Passing it in
     *     matters: the per-turn chat path ({@code AgentChatService}) already
     *     holds the plan, and a {@code FREE} tenant then answers this with no
     *     database round trip at all - the same short-circuit the inline
     *     expression got for free from {@code ||}.
     */
    boolean usesPlatformPool(String tenantId, TenantPlan plan);

    /**
     * {@link #usesPlatformPool(String, TenantPlan)} for a caller that does not
     * already hold the tenant's plan - the implementation resolves plan and
     * grant together, so this is one lookup, not two.
     */
    default boolean usesPlatformPool(String tenantId) {
        return usesPlatformPool(tenantId, null);
    }

    /**
     * Whether this plan may bring-your-own-key any premium LLM provider
     * (Anthropic/OpenAI/Groq/... - see {@code ProviderService#activate}, the
     * gate this backs). Unlike {@link #plan} this is read from the
     * data-driven {@code control_plane.plan_limits.byok_allowed} column, so a
     * platform-admin flipping it in the web-admin plan-limits editor takes
     * effect at runtime (Redis-evicted on save - see {@code PlanLimitsService
     * #update}) rather than being wired to a hardcoded {@code plan == FREE}
     * check. FREE seeds {@code false}, PREMIUM/UNLIMITED {@code true}.
     */
    boolean byokAllowed(String tenantId);

    /**
     * Whether this deployment's default LLM source is the platform-hosted pool
     * ({@code app.llm.default-source}: {@code platform-pool}, or {@code auto}
     * with a pool actually configured). When false there is no pool to fall
     * back on, so callers must use the tenant's own provider key. The default
     * (true) keeps hosted-SaaS behaviour for implementations that do not care.
     */
    default boolean platformPoolAvailable() {
        return true;
    }

    /**
     * Whether this plan may connect a WhatsApp Business channel (Meta manual
     * BYOK or Embedded Signup - see {@code MetaWhatsAppCredentialService
     * #activate}). Data-driven from {@code plan_limits.whatsapp_allowed}, same
     * runtime-editable posture as {@link #byokAllowed}. FREE seeds {@code
     * false}, PREMIUM/UNLIMITED {@code true}.
     */
    boolean whatsappAllowed(String tenantId);

    /**
     * Whether this plan may white-label the product (remove VegaDūta
     * branding). Data-driven from {@code plan_limits.white_label}. Only
     * UNLIMITED seeds {@code true} today.
     */
    boolean whiteLabel(String tenantId);

    /**
     * The tenant's white-label brand display name for PUBLIC surfaces (the
     * {@code /agent/{shareId}} share viewer), in EFFECTIVE form: empty unless
     * the plan's {@link #whiteLabel} entitlement is currently true AND the
     * tenant has actually set a name via {@code PUT /api/tenant/settings}.
     * Gating the read (not just the write) here means a downgrade off
     * UNLIMITED instantly restores VegaDuta branding on every public surface
     * without touching the stored value - and an UNLIMITED re-upgrade gets it
     * back. Storage is plan-agnostic on {@code Tenant} (V162), same pattern
     * as {@link #usePlatformLlm}.
     */
    Optional<String> brandDisplayName(String tenantId);

    /**
     * The tenant's white-label logo URL (https-only, validated at write time
     * by {@code TenantSettingsController}) - same effective-form semantics as
     * {@link #brandDisplayName}: empty unless {@link #whiteLabel} is true and
     * a value is set.
     */
    Optional<String> brandLogoUrl(String tenantId);

    /**
     * Hard cap on knowledge-base collections this tenant may have, or empty
     * for "no plan-level cap." Data-driven from {@code
     * plan_limits.max_kb_collections} ({@code -1} sentinel -> empty), same
     * "standing ceiling, not just at creation" posture as {@link #maxAgents}.
     */
    Optional<Integer> maxKbCollections(String tenantId);

    /**
     * Whether this plan may run continuous live-vision sessions (the WATCH/
     * LIVE camera and screen modes - see {@code VisionSessionService} in
     * {@code core.agents} and V180). Data-driven from {@code
     * plan_limits.live_vision_allowed}, same runtime-editable posture as
     * {@link #byokAllowed}. FREE seeds {@code false} (its continuous-vision
     * story is on-device only), every paid plan {@code true}. One-off
     * SNAPSHOT frames are deliberately NOT gated by this - they ride the
     * per-user daily frame quota instead.
     */
    boolean liveVisionAllowed(String tenantId);

    /**
     * Whether this plan may connect a third-party channel other than the
     * always-free ones (Telegram + Email): Slack, Discord, Teams, X, Twilio
     * SMS/voice, Meta Messenger/Instagram. Data-driven from {@code
     * plan_limits.external_channels_allowed} (V470), same runtime-editable
     * posture as {@link #byokAllowed}. FREE seeds {@code false}, every paid
     * plan {@code true}. WhatsApp keeps its own {@link #whatsappAllowed}
     * flag for the Meta BYOK/Embedded-Signup and QR-pairing paths - it is
     * the flagship paid channel and is gated on that flag, not this one.
     */
    boolean externalChannelsAllowed(String tenantId);

    /**
     * Whether this plan may mint MCP server access-grant API keys ({@code
     * POST /api/mcpserver/grants}) - the key material that authenticates
     * both {@code /api/dev/v1/**} (developer REST API) and {@code /mcp/**}
     * (MCP streamable HTTP), so one flag gates programmatic access.
     * Data-driven from {@code plan_limits.developer_api_allowed} (V470).
     * FREE seeds {@code false}, every paid plan {@code true}. Existing
     * grants keep working on downgrade (their last_verified_at polling
     * still validates) - the entitlement gates minting NEW capability,
     * matching the "downgrade doesn't break stored state" convention the
     * other feature flags follow.
     */
    boolean developerApiAllowed(String tenantId);

    /**
     * Whether this plan may configure a tenant-owned SAML/OIDC IdP ({@code
     * PUT /api/tenant/sso}). Data-driven from {@code
     * plan_limits.sso_allowed} (V470). FREE seeds {@code false}, PREMIUM/
     * UNLIMITED {@code true}.
     */
    boolean ssoAllowed(String tenantId);

    /**
     * Whether this plan may provision users through the SCIM-lite surface -
     * minting SCIM tokens ({@code POST /api/tenant/sso/scim-token}) AND
     * every {@code /scim/v2/**} operation those tokens authenticate.
     * Data-driven from {@code plan_limits.scim_allowed} (V470). UNLIMITED
     * only - one tier above {@link #ssoAllowed}, matching the usual market
     * split (directory sync is the enterprise-tier feature).
     */
    boolean scimAllowed(String tenantId);

    /**
     * Whether this plan may export a workflow's portable DSL ({@code GET
     * /api/workflows/{id}/export}). Data-driven from {@code
     * plan_limits.workflow_export_allowed} (V470). Only UNLIMITED seeds
     * {@code true} - taking work OUT of the platform is the top-tier
     * surface; import stays open to every plan.
     */
    boolean workflowExportAllowed(String tenantId);

    /**
     * Whether this plan may configure UNATTENDED execution - agent
     * schedules ({@code /api/agent-schedules}) and event triggers ({@code
     * /api/agents/{id}/triggers}). Data-driven from {@code
     * plan_limits.automation_allowed} (V470). FREE seeds {@code false},
     * every paid plan {@code true}: a FREE tenant runs agents interactively;
     * hands-off automation that spends platform money while nobody is
     * watching is the paid tier's capability. Gates CONFIGURATION only -
     * an already-enabled schedule keeps firing after a downgrade (the same
     * "downgrade doesn't break stored state" convention the other flags
     * follow), and per-fire spend stays bounded by the hosted-pool quotas.
     */
    boolean automationAllowed(String tenantId);
}
