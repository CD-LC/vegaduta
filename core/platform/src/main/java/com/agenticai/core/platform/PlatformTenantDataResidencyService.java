package com.agenticai.core.platform;

import com.agenticai.core.security.DataResidencyRegion;
import com.agenticai.core.tenancy.Tenant;
import com.agenticai.core.tenancy.TenantRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Master Roadmap 2026 item #22 (GDPR/data-residency basics, §G) - backs {@code PUT
 * /api/platform/tenants/{tenantId}/data-residency}. Same shape as {@link PlatformTenantPlanService}
 * (that class's javadoc explains the "bare summary, no re-run of the full metrics roster" pattern
 * this method reuses) - a platform-admin override, writing straight to the {@link Tenant} row.
 *
 * <p>See {@link DataResidencyRegion}'s javadoc for what this value is and is not: a declared,
 * admin-labeling fact about intended jurisdiction, not a trigger that moves any actual data
 * anywhere. This service does nothing beyond the plain field write - no data migration, no routing
 * change, because none exists yet for it to trigger.
 */
@Service
public class PlatformTenantDataResidencyService {

    private final TenantRepository tenantRepository;

    public PlatformTenantDataResidencyService(TenantRepository tenantRepository) {
        this.tenantRepository = tenantRepository;
    }

    @Transactional
    public PlatformTenantSummary changeDataResidencyRegion(
            UUID tenantId, DataResidencyRegion region) {
        Tenant tenant =
                tenantRepository
                        .findById(tenantId)
                        .orElseThrow(() -> new PlatformTenantNotFoundException(tenantId));
        tenant.declareDataResidencyRegion(region);
        tenantRepository.save(tenant);
        // Bare summary (no agent/workflow/user counts) - same reasoning
        // PlatformTenantPlanService#changePlan's javadoc already gives in
        // full for the identical trade-off; metricsComplete=false pairs
        // with the 0 counts below for the same "these zeros are not a real
        // reading" reason.
        return PlatformTenantSummary.withoutMetrics(tenant);
    }
}
