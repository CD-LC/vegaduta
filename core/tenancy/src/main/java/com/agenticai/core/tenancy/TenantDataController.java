package com.agenticai.core.tenancy;

import com.agenticai.core.tenancy.dto.TenantDataDeletionRequest;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Master Roadmap 2026 item #22 (GDPR/data-residency basics, §G) - the
 * tenant console's "Request data export" / "Request account deletion"
 * actions (Maintenance page, {@code web/app/admin/maintenance/page.js}).
 *
 * <p>Both endpoints are tenant-admin self-service, resolving "which tenant"
 * from {@link com.agenticai.core.security.TenantContext} the same way
 * {@link TenantSettingsController}/{@link TenantUpgradeController} already
 * do - never a path variable, never cross-tenant. Gated by {@code
 * SecurityConfig}'s {@code /api/tenant/data-export} and {@code
 * /api/tenant/data-deletion} -&gt; {@code ROLE_tenant-admin} rules, same
 * posture as those two sibling controllers.
 */
@RestController
@RequestMapping("/api/tenant")
@Profile("security")
public class TenantDataController {

    private final TenantDataExportService tenantDataExportService;
    private final TenantDataErasureService tenantDataErasureService;

    public TenantDataController(
        TenantDataExportService tenantDataExportService,
        TenantDataErasureService tenantDataErasureService
    ) {
        this.tenantDataExportService = tenantDataExportService;
        this.tenantDataErasureService = tenantDataErasureService;
    }

    /**
     * Returns the export as an immediate file download (also durably
     * written to object storage - see {@link TenantDataExportService}'s
     * javadoc) rather than a JSON body describing where to fetch it from
     * later - one request, one click, no second "now go download it"
     * step, which is enough for this "basics" pass.
     */
    @PostMapping("/data-export")
    public ResponseEntity<byte[]> exportData() {
        TenantDataExportService.ExportResult result = tenantDataExportService.exportCurrentTenantData();
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + result.filename() + "\"")
            .contentType(MediaType.APPLICATION_JSON)
            .body(result.bytes());
    }

    /**
     * Irreversible. See {@link TenantDataErasureService#eraseCurrentTenant}
     * for exactly what this does (deletes every Keycloak user for this
     * tenant, drops its Postgres schema, keeps a minimal terminal record on
     * the {@code Tenant} row itself) and {@link TenantDataDeletionRequest}
     * for why {@code confirmOrgName} is required.
     */
    @PostMapping("/data-deletion")
    public ResponseEntity<Void> deleteData(@Valid @RequestBody TenantDataDeletionRequest request) {
        tenantDataErasureService.eraseCurrentTenant(request.confirmOrgName());
        return ResponseEntity.noContent().build();
    }
}
