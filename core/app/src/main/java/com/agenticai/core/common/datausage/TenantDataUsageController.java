package com.agenticai.core.common.datausage;

import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * "How does our infrastructure use your data" transparency view - a
 * tenant-admin's own self-service read of which backend systems touch their
 * data and how (see {@link TenantDataUsageService}'s javadoc for the
 * per-system reasoning and fact sources).
 *
 * <p>Same tenant-scoped, admin-only posture as {@code TenantDataController}'s
 * {@code /api/tenant/data-export}/{@code /api/tenant/data-deletion} - "which
 * tenant" resolves from {@link com.agenticai.core.security.TenantContext},
 * never a path variable, and {@code SecurityConfig} gates {@code GET
 * /api/tenant/data-usage} to {@code ROLE_tenant-admin}, matching those two
 * sibling endpoints exactly. Deliberately its own controller (not folded into
 * {@code TenantDataController}) since that class lives in {@code
 * core/tenancy} and this one, for the cross-module-cycle reasons documented
 * on {@link TenantDataUsageService}, cannot.
 */
@RestController
@RequestMapping("/api/tenant")
@Profile("security")
public class TenantDataUsageController {

    private final TenantDataUsageService tenantDataUsageService;

    public TenantDataUsageController(TenantDataUsageService tenantDataUsageService) {
        this.tenantDataUsageService = tenantDataUsageService;
    }

    @GetMapping("/data-usage")
    public TenantDataUsageResponse dataUsage() {
        return tenantDataUsageService.currentTenantUsage();
    }
}
