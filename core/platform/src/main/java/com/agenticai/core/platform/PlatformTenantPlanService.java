package com.agenticai.core.platform;

import com.agenticai.core.tenancy.Tenant;
import com.agenticai.core.tenancy.TenantRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Backs the Subscription Plan management page (web-admin, port 3001) - a platform super-admin
 * directly setting a tenant's {@link com.agenticai.core.security.TenantPlan}. This is a super-admin
 * override, not a self-serve customer checkout: it writes {@code Tenant.plan} straight away (see
 * {@link Tenant#changePlan}), the same "changed deliberately, by a human with the authority to
 * change it" posture {@code accountType}'s javadoc already documents for a different field.
 * Whatever gate reads {@code TenantPlan} next - {@code TenantLimitsServiceImpl}'s caps, {@code
 * AgentChatService}'s FREE-tier hosted-pool routing, the free-tier agent/workflow edit-lock - reads
 * {@code Tenant.plan} fresh from the database on its very next call, so this takes effect
 * immediately, with no cache to bust and no tenant-side action required.
 *
 * <p>Deliberately does not touch Razorpay at all. A tenant that's paying through the gateway keeps
 * its existing {@code gatewaySubscriptionId} row untouched by this override - if a future
 * self-serve "Upgrade" button on the tenant console needs to actually create/change a paid
 * subscription, that's a distinct flow through {@link
 * com.agenticai.core.billing.gateway.PaymentGatewayClient}, not this one. This service exists for
 * the cases a payment gateway doesn't cover: comping a plan for a support case, correcting a tenant
 * stuck on the wrong tier, or running the platform's own eval/demo tenants on UNLIMITED.
 */
@Service
public class PlatformTenantPlanService {

    private final TenantRepository tenantRepository;

    public PlatformTenantPlanService(TenantRepository tenantRepository) {
        this.tenantRepository = tenantRepository;
    }

    @Transactional
    public PlatformTenantSummary changePlan(
            UUID tenantId, com.agenticai.core.security.TenantPlan plan) {
        Tenant tenant =
                tenantRepository
                        .findById(tenantId)
                        .orElseThrow(() -> new PlatformTenantNotFoundException(tenantId));
        tenant.changePlan(plan);
        tenantRepository.save(tenant);
        // Bare summary (no agent/workflow/user counts) - this endpoint's
        // caller (the Subscription Plan page) already has those from its own
        // GET /api/platform/tenants load and should merge just this
        // response's tenantId/name/status/plan into its existing row rather
        // than replacing the whole row. Re-running
        // PlatformTenantMetricsService's full three-repository-lookup dance
        // here for a single changed field would be a wasted round trip
        // through every tenant schema for nothing this response actually
        // needs. metricsComplete is false here (not true) because the three
        // counts genuinely weren't attempted on this call - per
        // PlatformTenantSummary's own javadoc, "0" must always pair with
        // metricsComplete=false so nothing downstream mistakes these zeros
        // for a real reading.
        return PlatformTenantSummary.withoutMetrics(tenant);
    }
}
