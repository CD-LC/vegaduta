package com.agenticai.core.platform;

/**
 * Body for {@code PUT /api/platform/plan-limits/{plan}} - the new caps for
 * that plan; {@code plan} itself comes from the path, same convention
 * {@link PlatformSettingUpdateRequest} already uses for {@code
 * /settings/{key}}. Field names match {@code web-admin}'s {@code
 * plans/page.tsx} {@code LIMIT_NUMBER_FIELDS}/{@code LIMIT_TOGGLE_FIELDS}
 * exactly - that page sends its full draft object (a copy of the last-GET
 * row with edited fields applied), which naturally satisfies every field
 * here in one shot.
 */
public record PlanLimitsUpdateRequest(
    int maxUsers,
    int maxAgents,
    int maxWorkflows,
    int maxKbCollections,
    boolean byokAllowed,
    boolean whatsappAllowed,
    boolean whiteLabel,
    // Live Vision (V180). Boxed Boolean where every older toggle
    // is a primitive, deliberately: web-admin's plans page sends its full
    // draft object built from the last GET - a draft captured by a build
    // that predates this field simply omits it, and a primitive would
    // silently coerce that omission to false, resetting the paid tiers'
    // seeded TRUE on the next unrelated edit. null means "leave unchanged"
    // (resolved against the current row in PlanLimitsController).
    Boolean liveVisionAllowed,
    // Freemium billing hardening (V470) - the five billing entitlements, all
    // boxed for the same stale-draft reason liveVisionAllowed is: an older
    // web-admin build omitting them must not silently revoke them.
    Boolean externalChannelsAllowed,
    Boolean developerApiAllowed,
    Boolean ssoAllowed,
    Boolean scimAllowed,
    Boolean workflowExportAllowed,
    Boolean automationAllowed
) {
}
