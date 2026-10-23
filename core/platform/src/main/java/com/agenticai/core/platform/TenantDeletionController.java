package com.agenticai.core.platform;

import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.edition.ConditionalOnEditionFeature;
import com.agenticai.core.security.edition.EditionFeature;
import com.agenticai.core.tenancy.MasterKeyInvalidException;
import com.agenticai.core.tenancy.MasterKeyMissingException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Platform-admin "delete tenant completely" control - {@code DELETE
 * /api/platform/tenants/{tenantId}}. Covered by the same {@code SecurityConfig} blanket {@code
 * /api/platform/**} -> {@code ROLE_platform-admin} rule as every other controller under this prefix
 * (see {@link PlanLimitsController}'s javadoc for the same posture) - no separate role check needed
 * here. What IS new here is a SECOND, independent gate on top of that role check: an {@code
 * X-Master-Key} header, constant-time-compared against the {@code PLATFORM_MASTER_KEY} secret,
 * fail-closed if that secret is unset - copying {@code CompGrantController}'s existing
 * service-token pattern exactly (that class is server-to-server auth for a different feature; this
 * one gates an already-authenticated human platform admin against an irreversible action, so the
 * codebase gets a second independent secret rather than reusing it).
 *
 * <p>Kept as its own small controller (see {@link PlanLimitsController}'s javadoc for the same
 * "don't bolt this onto the long-lived {@link PlatformController}" reasoning) delegating the real
 * work to {@link PlatformTenantDeletionOrchestrationService}, which is the one class that actually
 * knows about every system a tenant's data can live in.
 */
@ConditionalOnEditionFeature(EditionFeature.OPERATOR_CONSOLE)
@RestController
@RequestMapping("/api/platform/tenants")
public class TenantDeletionController {

    private final PlatformTenantDeletionOrchestrationService orchestrationService;
    private final String masterKey;

    public TenantDeletionController(
            PlatformTenantDeletionOrchestrationService orchestrationService,
            @Value("${app.platform.master-key:}") String masterKey) {
        this.orchestrationService = orchestrationService;
        this.masterKey = masterKey;
    }

    public record DeleteTenantRequest(String confirmTenantName) {}

    @DeleteMapping("/{tenantId}")
    public void deleteTenant(
            @PathVariable UUID tenantId,
            @RequestHeader(value = "X-Master-Key", required = false) String presentedMasterKey,
            @RequestBody DeleteTenantRequest request) {
        authenticateMasterKey(presentedMasterKey);
        String actorId = TenantContext.current().map(TenantContext::getSubject).orElse(null);
        orchestrationService.deleteTenant(tenantId, request.confirmTenantName(), actorId);
    }

    private void authenticateMasterKey(String presentedKey) {
        authenticateMasterKey(masterKey, presentedKey);
    }

    /**
     * Same fail-closed, constant-time-compare shape as {@code CompGrantController#authenticate}.
     * Static and package-visible so every other irreversible platform-admin action ({@link
     * DemoSandboxReclaimController}) applies the identical second gate rather than a
     * re-implementation of it.
     */
    public static void authenticateMasterKey(String masterKey, String presentedKey) {
        if (masterKey == null || masterKey.isBlank()) {
            throw new MasterKeyMissingException(
                    "PLATFORM_MASTER_KEY is not configured on this server; tenant deletion is"
                            + " disabled.");
        }
        if (presentedKey == null || presentedKey.isBlank()) {
            throw new MasterKeyMissingException("Missing X-Master-Key header.");
        }
        boolean matches =
                MessageDigest.isEqual(
                        masterKey.getBytes(StandardCharsets.UTF_8),
                        presentedKey.getBytes(StandardCharsets.UTF_8));
        if (!matches) {
            throw new MasterKeyInvalidException("Invalid X-Master-Key.");
        }
    }
}
