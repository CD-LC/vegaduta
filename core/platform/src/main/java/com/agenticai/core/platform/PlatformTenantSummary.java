package com.agenticai.core.platform;

/**
 * One row of {@code GET /api/platform/tenants}'s cross-tenant roster - a super-admin's "how is each
 * tenant doing" view.
 *
 * <p>{@code metricsComplete} is {@code false} whenever any one of {@code agentCount}/{@code
 * workflowCount}/{@code userCount} could not be determined for this tenant (see {@link
 * PlatformTenantMetricsService}) - the corresponding count is reported as {@code 0} in that case, a
 * real "couldn't determine this" signal via the boolean rather than a silently misleading zero.
 *
 * <p>{@code dataResidencyRegion} - Master Roadmap 2026 item #22 (GDPR/ data-residency basics, §G) -
 * the tenant's declared-intent jurisdiction, {@code null} until a platform admin sets one via
 * {@code PUT /api/platform/tenants/{tenantId}/data-residency}. See {@link
 * com.agenticai.core.security.DataResidencyRegion}'s javadoc for why this is
 * informational/admin-labeling only, never an enforced routing signal.
 *
 * <p>Added 2026-10-09 (all nullable - {@code null} means "could not be determined" or "not
 * applicable", never zero): {@code createdAt}; {@code ownerEmail} (the earliest TENANT_ADMIN {@code
 * app_user}, else the earliest user at all); {@code lastActivityAt} (the later of any member's
 * {@code app_user.last_seen_at} and the tenant's last LLM call); {@code spend30dUsd}/{@code
 * hostedSpend30dUsd}/{@code llmCalls30d} (trailing 30 UTC days, all providers / PLATFORM_HOSTED
 * only / call count - the same rollup-plus-live-today source as {@code /api/platform/spend/*};
 * ACTIVE tenants only). {@code status} was already present.
 *
 * <p>V477 (2026-10-09): {@code spendCapUsd} (monthly hosted-pool cap, null = none), {@code
 * capMonthToDateUsd}/{@code capBlocked} (null when there is no cap, or the month-to-date figure
 * could not be determined).
 */
public record PlatformTenantSummary(
        java.util.UUID tenantId,
        String name,
        String status,
        com.agenticai.core.security.TenantPlan plan,
        long agentCount,
        long workflowCount,
        long userCount,
        boolean metricsComplete,
        boolean emergencyStopped,
        com.agenticai.core.security.DataResidencyRegion dataResidencyRegion,
        java.time.Instant createdAt,
        String ownerEmail,
        java.time.Instant lastActivityAt,
        java.math.BigDecimal spend30dUsd,
        java.math.BigDecimal hostedSpend30dUsd,
        Long llmCalls30d,
        java.math.BigDecimal spendCapUsd,
        java.math.BigDecimal capMonthToDateUsd,
        Boolean capBlocked) {

    /**
     * The bare row the single-field mutation endpoints (plan, data residency) return: identity +
     * stored fields only, counts not attempted (so {@code metricsComplete=false}), every derived
     * figure {@code null}.
     */
    static PlatformTenantSummary withoutMetrics(com.agenticai.core.tenancy.Tenant tenant) {
        return new PlatformTenantSummary(
                tenant.getId(),
                tenant.getName(),
                tenant.getStatus().name(),
                tenant.getPlan(),
                0L,
                0L,
                0L,
                false,
                tenant.isEmergencyStopped(),
                tenant.getDataResidencyRegion(),
                tenant.getCreatedAt(),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
    }
}
