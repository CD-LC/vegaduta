package com.agenticai.core.tenancy.dto;

import com.agenticai.core.security.AccountType;
import com.agenticai.core.security.ExperienceMode;
import com.agenticai.core.security.TenantPlan;
import java.util.UUID;

/**
 * What the web console needs to render the "Simple vs Advanced" toggle and
 * the account-type/limits summary in Admin Overview - {@code maxAgents} of
 * {@code null} means "no plan-level cap" (see {@code TenantLimits#maxAgents}).
 *
 * <p>{@code organizationName} (added for the HUD topbar rebuild) is just
 * {@code Tenant.name} - the same unique org name chosen at signup - exposed
 * here rather than added to a new endpoint, since every caller of this one
 * already resolves the current tenant's row and every topbar render already
 * fetches this response for the plan chip.
 *
 * <p>{@code plan}/{@code maxWorkflows} (added for the Billing page, #957) -
 * same "no cap" {@code null} convention as {@code maxAgents}, straight off
 * {@link com.agenticai.core.security.TenantLimits#plan}/{@code maxWorkflows}
 * (already computed for every other caller of this response's fields, just
 * not previously surfaced here since nothing needed to show it back to the
 * tenant before now).
 *
 * <p>{@code usePlatformLlm} (issue report: "every non free tenant should get
 * an option to select use platform llm") - a PREMIUM/UNLIMITED tenant's own
 * opt-in, straight off {@code Tenant#isUsePlatformLlm}. Always effectively
 * {@code true}-in-behavior (but reported as the tenant's stored flag, which
 * may be {@code false}) for a FREE tenant, since FREE always routes through
 * the hosted pool regardless of this flag - see {@code TenantLimits
 * #usePlatformLlm}'s javadoc.
 *
 * <p>{@code tenantId} (Academy Fast-Track Scholarship, CORE-COMP-GRANT-CONTRACT.md)
 * is the tenant's control-plane {@code Tenant.id} - the Academy denormalises it
 * onto a scholarship claim at claim time from {@code GET /api/tenant/settings} so
 * that the later one-click {@code POST /api/tenant/comp-grant} has a tenant id to
 * target. Same best-effort sourcing as {@code organizationName} (both come off the
 * resolved {@code Tenant} row); {@code null} only if that row can't be resolved,
 * which would mean SecurityConfig let an unprovisioned tenant through.
 *
 * <p>{@code byokAllowed}/{@code whatsappAllowed}/{@code whiteLabel}/{@code
 * maxKbCollections}/{@code liveVisionAllowed} are the plan's feature
 * entitlements, read from the
 * data-driven {@code control_plane.plan_limits} row via {@code TenantLimits}
 * (see that interface's per-method javadoc). They are what the tenant-facing
 * Features page renders enabled-vs-disabled per plan, and - crucially - what
 * makes a platform-admin's edit in the web-admin plan-limits editor actually
 * reach the tenant app (previously these columns were written but read by
 * nothing). {@code maxKbCollections} of {@code null} means "no cap," same
 * convention as {@code maxAgents}/{@code maxWorkflows}.
 *
 * <p>{@code brandDisplayName}/{@code brandLogoUrl} (white-label minimum-real
 * build, item C2) are the tenant's STORED white-label branding, straight off
 * the {@code Tenant} row - readable on every plan (so a downgraded tenant can
 * still see what they had configured), even though public surfaces only
 * render them while {@code whiteLabel} is true (that read goes through {@code
 * TenantLimits#brandDisplayName}'s entitlement-gated effective form instead).
 * {@code null} means "not set" - platform branding everywhere.
 *
 * <p>{@code freeMode} (freemium billing hardening, V470) reports {@code
 * platform.free-mode.enabled} straight off {@code FreeModeService} - while
 * it is true every entitlement resolves true for every tenant and the
 * billing/checkout UI is meaningless, so the web app reads this to decide
 * whether to render upgrade surfaces at all (server-side truth, not the
 * build-time NEXT_PUBLIC_FREE_MODE bake, so a runtime flag flip takes
 * effect on the next fetch).
 *
 * <p>{@code externalChannelsAllowed}/{@code developerApiAllowed}/{@code
 * ssoAllowed}/{@code scimAllowed}/{@code workflowExportAllowed} (V470)
 * are the plan's billing entitlements for the paid channel/dev/SSO/SCIM/
 * export surfaces - same {@code plan_limits}-backed read as {@code
 * byokAllowed}, rendered on the tenant-facing Features page.
 */
public record TenantSettingsResponse(
    UUID tenantId,
    AccountType accountType,
    ExperienceMode experienceMode,
    int maxUsers,
    Integer maxAgents,
    String organizationName,
    TenantPlan plan,
    Integer maxWorkflows,
    boolean usePlatformLlm,
    boolean byokAllowed,
    boolean whatsappAllowed,
    boolean whiteLabel,
    Integer maxKbCollections,
    // Live Vision (V180) - whether this plan may run continuous
    // WATCH/LIVE vision sessions; what the Features page renders for the
    // "VegaDuta Sight" row. Same plan_limits-backed read as byokAllowed.
    boolean liveVisionAllowed,
    String brandDisplayName,
    String brandLogoUrl,
    boolean freeMode,
    boolean externalChannelsAllowed,
    boolean developerApiAllowed,
    boolean ssoAllowed,
    boolean scimAllowed,
    boolean workflowExportAllowed,
    boolean automationAllowed
) {
}
