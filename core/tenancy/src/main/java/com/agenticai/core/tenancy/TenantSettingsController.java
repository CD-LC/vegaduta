package com.agenticai.core.tenancy;

import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantLimits;
import com.agenticai.core.tenancy.dto.TenantSettingsResponse;
import com.agenticai.core.tenancy.dto.UpdateExperienceModeRequest;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Backs the "which experience do you want" control in Admin Overview and
 * the account-type/limits summary shown there - {@code accountType} itself
 * is read-only here (see {@code Tenant.accountType}'s javadoc for why it's
 * a signup-time-only decision); only {@code experienceMode} can change
 * after the fact. Gated by {@code SecurityConfig}'s {@code /api/tenant/**}
 * -> {@code ROLE_tenant-admin} rule, same posture as {@code
 * UserAdminController}.
 */
@RestController
@RequestMapping("/api/tenant/settings")
@Profile("security")
public class TenantSettingsController {

    private final TenantRepository tenantRepository;
    private final TenantLimits tenantLimits;
    private final FreeModeService freeModeService;

    public TenantSettingsController(
        TenantRepository tenantRepository, TenantLimits tenantLimits, FreeModeService freeModeService) {
        this.tenantRepository = tenantRepository;
        this.tenantLimits = tenantLimits;
        this.freeModeService = freeModeService;
    }

    @GetMapping
    public TenantSettingsResponse get() {
        String tenantId = tenantId();
        // organizationName (HUD topbar) and id (Academy comp-grant, see
        // TenantSettingsResponse#tenantId) are the two fields here that aren't
        // already cached on TenantLimits - both come straight off the Tenant
        // row itself, same lookup updateExperienceMode below already does.
        // Best-effort: a missing row would mean SecurityConfig let an
        // unprovisioned tenant through, which shouldn't happen, but the
        // topbar just shows no org name (and a null id) rather than failing
        // this whole response over it.
        Tenant tenant = tenantRepository.findBySchemaName(tenantId).orElse(null);
        UUID id = tenant != null ? tenant.getId() : null;
        String organizationName = tenant != null ? tenant.getName() : null;
        return new TenantSettingsResponse(
            id,
            tenantLimits.accountType(tenantId),
            tenantLimits.experienceMode(tenantId),
            tenantLimits.maxUsers(tenantId),
            tenantLimits.maxAgents(tenantId).orElse(null),
            organizationName,
            tenantLimits.plan(tenantId),
            tenantLimits.maxWorkflows(tenantId).orElse(null),
            tenantLimits.usePlatformLlm(tenantId),
            tenantLimits.byokAllowed(tenantId),
            tenantLimits.whatsappAllowed(tenantId),
            tenantLimits.whiteLabel(tenantId),
            tenantLimits.maxKbCollections(tenantId).orElse(null),
            tenantLimits.liveVisionAllowed(tenantId),
            // STORED branding, readable on every plan (see the response
            // record's javadoc) - the entitlement-gated effective read the
            // public share surface uses is TenantLimits#brandDisplayName.
            tenant != null ? tenant.getBrandDisplayName() : null,
            tenant != null ? tenant.getBrandLogoUrl() : null,
            freeModeService.isEnabled(),
            tenantLimits.externalChannelsAllowed(tenantId),
            tenantLimits.developerApiAllowed(tenantId),
            tenantLimits.ssoAllowed(tenantId),
            tenantLimits.scimAllowed(tenantId),
            tenantLimits.workflowExportAllowed(tenantId),
            tenantLimits.automationAllowed(tenantId)
        );
    }

    @PutMapping
    public TenantSettingsResponse updateExperienceMode(@Valid @RequestBody UpdateExperienceModeRequest request) {
        String tenantId = tenantId();
        Tenant tenant = tenantRepository.findBySchemaName(tenantId)
            .orElseThrow(() -> new IllegalStateException("No Tenant row for schema '" + tenantId + "'"));
        tenant.setExperienceMode(request.experienceMode());
        // Issue report: "every non free tenant should get an option to select
        // use platform llm" - null means "not sent, leave unchanged" (see
        // UpdateExperienceModeRequest's javadoc); a FREE tenant can still
        // technically flip this bit, it's just inert for them since
        // AgentChatService.buildChatClientFor always routes FREE through the
        // hosted pool regardless of this flag.
        if (request.usePlatformLlm() != null) {
            tenant.setUsePlatformLlm(request.usePlatformLlm());
        }
        // White-label branding (item C2): same null-means-unchanged
        // convention as usePlatformLlm above, but the WRITE is gated on the
        // plan's white_label entitlement - the two fields are the whole
        // point of the entitlement, so a FREE/PREMIUM tenant attempting to
        // set (or clear) them gets an upgrade-oriented 403, never a silent
        // no-op. Reads stay open to every plan (see get()).
        if (request.brandDisplayName() != null || request.brandLogoUrl() != null) {
            if (!tenantLimits.whiteLabel(tenantId)) {
                throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "White-labelling is not included in your plan - upgrade to Unlimited to replace "
                        + "VegaDuta branding with your own on shared agent pages."
                );
            }
            // Per-field null-means-unchanged, blank-means-clear; validated
            // here (not only via @Size) so the rules hold for every caller.
            String name = request.brandDisplayName() != null
                ? normalizeBrandDisplayName(request.brandDisplayName())
                : tenant.getBrandDisplayName();
            String logoUrl = request.brandLogoUrl() != null
                ? normalizeBrandLogoUrl(request.brandLogoUrl())
                : tenant.getBrandLogoUrl();
            tenant.updateBranding(name, logoUrl);
        }
        tenantRepository.save(tenant);
        return get();
    }

    /** Blank clears; otherwise trimmed, max 64 chars (the V162 column width). */
    private static String normalizeBrandDisplayName(String raw) {
        String trimmed = raw.strip();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.length() > 64) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST, "brandDisplayName must be at most 64 characters.");
        }
        return trimmed;
    }

    /**
     * Blank clears; otherwise must be a well-formed absolute {@code https}
     * URL with a host, max 512 chars (the V162 column width). https-only on
     * purpose: this URL is rendered as an {@code <img src>} on a PUBLIC
     * unauthenticated page, so plain http (mixed content) and every non-http
     * scheme ({@code javascript:}, {@code data:}, ...) are rejected at write
     * time rather than trusted to the renderer.
     */
    private static String normalizeBrandLogoUrl(String raw) {
        String trimmed = raw.strip();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.length() > 512) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST, "brandLogoUrl must be at most 512 characters.");
        }
        URI uri;
        try {
            uri = new URI(trimmed);
        } catch (java.net.URISyntaxException e) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST, "brandLogoUrl must be a valid https URL.");
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getHost().isBlank()) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST, "brandLogoUrl must be an absolute https URL (e.g. https://example.com/logo.png).");
        }
        return trimmed;
    }

    private String tenantId() {
        return TenantContext.current().map(TenantContext::getTenantId)
            .orElseThrow(() -> new IllegalStateException(
                "No TenantContext for an authenticated /api/tenant/settings request - SecurityConfig should have blocked this"));
    }
}
