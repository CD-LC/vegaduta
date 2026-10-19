package com.agenticai.core.tenancy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.security.AccountType;
import com.agenticai.core.security.ExperienceMode;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantLimits;
import com.agenticai.core.security.TenantPlan;
import com.agenticai.core.tenancy.dto.TenantSettingsResponse;
import com.agenticai.core.tenancy.dto.UpdateExperienceModeRequest;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * White-label minimum-real build (PRICING-MONETIZATION-AUDIT-2026-07-31.md
 * item C2) - the settings write-gate and logo-URL validation on {@code PUT
 * /api/tenant/settings}. Plain unit tests calling the controller method
 * directly, no MockMvc/web layer - the established convention in this
 * module's tests (see {@code CompGrantControllerTest}'s header note).
 *
 * <p>The gate under test: {@code brandDisplayName}/{@code brandLogoUrl} are
 * writable only when the plan's {@code white_label} entitlement is true
 * (UNLIMITED today), 403 with an upgrade-oriented message otherwise; the
 * logo URL must be an absolute https URL; blank clears; null leaves the
 * stored value unchanged. Reads stay open to every plan.
 */
class TenantSettingsControllerTest {

    private static final String TENANT_ID = "acme";

    private TenantRepository tenantRepository;
    private TenantLimits tenantLimits;
    private FreeModeService freeModeService;
    private TenantSettingsController controller;
    private Tenant tenant;

    @BeforeEach
    void setUp() {
        tenantRepository = mock(TenantRepository.class);
        tenantLimits = mock(TenantLimits.class);
        freeModeService = mock(FreeModeService.class);
        controller = new TenantSettingsController(tenantRepository, tenantLimits, freeModeService);

        tenant = new Tenant(
            "Acme", TenantPlan.UNLIMITED, TENANT_ID, "agentic-ai", AccountType.B2C, ExperienceMode.SIMPLE);
        when(tenantRepository.findBySchemaName(TENANT_ID)).thenReturn(Optional.of(tenant));
        // get() re-reads everything through TenantLimits after a PUT; the
        // branding fields under test come off the Tenant row itself, so
        // Mockito's defaults (null/false/0) are fine for the rest except the
        // two Optional-returning caps get() unwraps.
        when(tenantLimits.maxAgents(TENANT_ID)).thenReturn(Optional.empty());
        when(tenantLimits.maxWorkflows(TENANT_ID)).thenReturn(Optional.empty());
        when(tenantLimits.maxKbCollections(TENANT_ID)).thenReturn(Optional.empty());

        TenantContext.set(TenantContext.of(TENANT_ID, "tenant-admin@acme", Set.of("tenant-admin")));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // ------------------------------------------------------------------
    // Write-gate: the white_label entitlement decides who may write.

    @Test
    void brandingWriteWithoutWhiteLabelEntitlementIs403AndPersistsNothing() {
        // FREE and PREMIUM both seed white_label=false - the entitlement,
        // not the plan name, is what the gate reads.
        when(tenantLimits.whiteLabel(TENANT_ID)).thenReturn(false);

        ResponseStatusException e = assertThrows(
            ResponseStatusException.class,
            () -> controller.updateExperienceMode(new UpdateExperienceModeRequest(
                ExperienceMode.SIMPLE, null, "Acme Corp", "https://cdn.acme.example/logo.png"))
        );
        assertEquals(HttpStatus.FORBIDDEN, e.getStatusCode());
        // Upgrade-oriented copy, not a bare "forbidden".
        org.junit.jupiter.api.Assertions.assertTrue(e.getReason().toLowerCase().contains("upgrade"));
        verify(tenantRepository, never()).save(ArgumentMatchers.any());
        assertNull(tenant.getBrandDisplayName());
        assertNull(tenant.getBrandLogoUrl());
    }

    @Test
    void clearingBrandingWithoutEntitlementIsAlso403() {
        // Blank means "clear" - still a branding WRITE, still gated. A
        // downgraded tenant doesn't need it anyway: the public read is
        // entitlement-gated on its own (TenantLimits#brandDisplayName).
        when(tenantLimits.whiteLabel(TENANT_ID)).thenReturn(false);

        ResponseStatusException e = assertThrows(
            ResponseStatusException.class,
            () -> controller.updateExperienceMode(new UpdateExperienceModeRequest(
                ExperienceMode.SIMPLE, null, "", null))
        );
        assertEquals(HttpStatus.FORBIDDEN, e.getStatusCode());
        verify(tenantRepository, never()).save(ArgumentMatchers.any());
    }

    @Test
    void brandingWriteWithWhiteLabelEntitlementPersistsAndEchoesBack() {
        when(tenantLimits.whiteLabel(TENANT_ID)).thenReturn(true);

        TenantSettingsResponse response = controller.updateExperienceMode(new UpdateExperienceModeRequest(
            ExperienceMode.SIMPLE, null, "  Acme Corp  ", "https://cdn.acme.example/logo.png"));

        assertEquals("Acme Corp", tenant.getBrandDisplayName()); // trimmed
        assertEquals("https://cdn.acme.example/logo.png", tenant.getBrandLogoUrl());
        assertEquals("Acme Corp", response.brandDisplayName());
        assertEquals("https://cdn.acme.example/logo.png", response.brandLogoUrl());
        verify(tenantRepository).save(tenant);
    }

    @Test
    void nullBrandingFieldsLeaveStoredValuesUnchanged() {
        // The existing null-means-unchanged convention (usePlatformLlm) holds
        // per branding field too - an all-null PUT never consults the
        // entitlement for the WRITE gate, so pre-existing experienceMode-only
        // callers on ANY plan keep working unchanged. (get(), called at the
        // end of every PUT to build the response, always reads
        // tenantLimits.whiteLabel() to populate the pre-existing entitlement
        // field on TenantSettingsResponse - that's unrelated to the branding
        // write-gate and not what this test is asserting.)
        when(tenantLimits.whiteLabel(TENANT_ID)).thenReturn(false);
        tenant.updateBranding("Acme Corp", "https://cdn.acme.example/logo.png");

        controller.updateExperienceMode(new UpdateExperienceModeRequest(
            ExperienceMode.ADVANCED, null, null, null));

        assertEquals("Acme Corp", tenant.getBrandDisplayName());
        assertEquals("https://cdn.acme.example/logo.png", tenant.getBrandLogoUrl());
        verify(tenantRepository).save(tenant);
    }

    @Test
    void blankBrandingFieldsClearStoredValues() {
        when(tenantLimits.whiteLabel(TENANT_ID)).thenReturn(true);
        tenant.updateBranding("Acme Corp", "https://cdn.acme.example/logo.png");

        controller.updateExperienceMode(new UpdateExperienceModeRequest(
            ExperienceMode.SIMPLE, null, "  ", "  "));

        assertNull(tenant.getBrandDisplayName());
        assertNull(tenant.getBrandLogoUrl());
        verify(tenantRepository).save(tenant);
    }

    // ------------------------------------------------------------------
    // Logo-URL validation: https-only, absolute, bounded - enforced in the
    // controller itself so the rules hold even off the bean-validation path.

    @Test
    void httpLogoUrlIsRejected() {
        when(tenantLimits.whiteLabel(TENANT_ID)).thenReturn(true);
        assertBadLogoUrl("http://cdn.acme.example/logo.png");
    }

    @Test
    void javascriptSchemeLogoUrlIsRejected() {
        when(tenantLimits.whiteLabel(TENANT_ID)).thenReturn(true);
        assertBadLogoUrl("javascript:alert(1)");
    }

    @Test
    void schemeRelativeAndMalformedLogoUrlsAreRejected() {
        when(tenantLimits.whiteLabel(TENANT_ID)).thenReturn(true);
        assertBadLogoUrl("//cdn.acme.example/logo.png"); // no scheme
        assertBadLogoUrl("https://"); // no host
        assertBadLogoUrl("not a url at all");
    }

    @Test
    void overlongLogoUrlIsRejected() {
        when(tenantLimits.whiteLabel(TENANT_ID)).thenReturn(true);
        assertBadLogoUrl("https://cdn.acme.example/" + "a".repeat(512) + ".png");
    }

    @Test
    void overlongDisplayNameIsRejected() {
        when(tenantLimits.whiteLabel(TENANT_ID)).thenReturn(true);

        ResponseStatusException e = assertThrows(
            ResponseStatusException.class,
            () -> controller.updateExperienceMode(new UpdateExperienceModeRequest(
                ExperienceMode.SIMPLE, null, "x".repeat(65), null))
        );
        assertEquals(HttpStatus.BAD_REQUEST, e.getStatusCode());
        verify(tenantRepository, never()).save(ArgumentMatchers.any());
    }

    private void assertBadLogoUrl(String url) {
        ResponseStatusException e = assertThrows(
            ResponseStatusException.class,
            () -> controller.updateExperienceMode(new UpdateExperienceModeRequest(
                ExperienceMode.SIMPLE, null, null, url))
        );
        assertEquals(HttpStatus.BAD_REQUEST, e.getStatusCode());
        assertNull(tenant.getBrandLogoUrl());
        verify(tenantRepository, never()).save(ArgumentMatchers.any());
    }
}
