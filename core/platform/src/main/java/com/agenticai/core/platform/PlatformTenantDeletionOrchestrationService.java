package com.agenticai.core.platform;

import com.agenticai.core.codeintel.CodeIntelClient;
import com.agenticai.core.tenancy.TenantDataErasureService;
import com.agenticai.core.tenancy.TenantErasureResult;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Platform-admin "delete tenant completely" control - the top of the call stack. {@link
 * TenantDeletionController} handles auth (role + master key) and confirmation; this class runs the
 * actual sequence and is the ONE place in the whole platform that knows about every system a
 * tenant's data can live in: Postgres/Keycloak/S3/Redis (via {@link
 * TenantDataErasureService#eraseTenantById}, which cannot reach the two service below - see that
 * method's own javadoc for why), and code-intel.
 *
 * <p>Lives in {@code core/platform} specifically because it can depend on both {@code core/tenancy}
 * and {@code core/codeintel} without a cycle ({@code core/codeintel} is a leaf-ish module that does
 * not depend back on {@code core/platform}).
 */
@Service
public class PlatformTenantDeletionOrchestrationService {

    private static final Logger log =
            LoggerFactory.getLogger(PlatformTenantDeletionOrchestrationService.class);

    private final TenantDataErasureService tenantDataErasureService;
    private final Optional<CodeIntelClient> codeIntelClient;

    public PlatformTenantDeletionOrchestrationService(
            TenantDataErasureService tenantDataErasureService,
            Optional<CodeIntelClient> codeIntelClient) {
        this.tenantDataErasureService = tenantDataErasureService;
        this.codeIntelClient = codeIntelClient;
    }

    public void deleteTenant(UUID tenantId, String confirmTenantName, String actorId) {
        // Everything this method itself can throw (tenant not found, already
        // erased, name mismatch, a schema-drop failure) propagates straight
        // out - core/tenancy already recorded the FAILED outcome in that
        // case (see eraseTenantById's own javadoc). Step 2 below is
        // deliberately best-effort and never rethrows: the tenant's core data
        // is already gone by the time it runs, so there is nothing left to
        // protect by failing loudly, only orphaned state elsewhere to warn
        // about.
        TenantErasureResult result =
                tenantDataErasureService.eraseTenantById(tenantId, confirmTenantName, actorId);

        deleteCodeIntelData(result.schemaName());
    }

    /**
     * Demo-sandbox reclaim (2026-10-09) - {@link #deleteTenant} for a legacy per-visitor {@code
     * /try} sandbox: no typed-name confirmation, guarded instead by {@link
     * TenantDataErasureService#eraseDemoTenantById}'s own is_demo / not-the-shared-sandbox checks.
     * Same best-effort follow-up step as a normal deletion.
     */
    public void deleteDemoTenant(UUID tenantId, String sharedDemoSchema, String actorId) {
        TenantErasureResult result =
                tenantDataErasureService.eraseDemoTenantById(tenantId, sharedDemoSchema, actorId);
        deleteCodeIntelData(result.schemaName());
    }

    private void deleteCodeIntelData(String schemaName) {
        if (codeIntelClient.isEmpty()) {
            log.info(
                    "code-intel not enabled on this deployment - skipping tenant-directory"
                            + " cleanup");
            return;
        }
        try {
            // schemaName, not the Tenant UUID - see TenantErasureResult's
            // javadoc for why: every other CodeIntelClient call site sends
            // X-Tenant-Id as the schema name (TenantContext.getTenantId()),
            // and code-intel's on-disk directories are named to match.
            codeIntelClient.get().deleteTenantData(schemaName);
        } catch (RuntimeException e) {
            log.warn(
                    "Failed to delete code-intel data for tenant schema '{}' during deletion -"
                            + " continuing",
                    schemaName,
                    e);
        }
    }
}
