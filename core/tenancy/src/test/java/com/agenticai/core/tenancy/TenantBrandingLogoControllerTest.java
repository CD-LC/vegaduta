package com.agenticai.core.tenancy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.security.AccountType;
import com.agenticai.core.security.ExperienceMode;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantLimits;
import com.agenticai.core.security.TenantPlan;
import com.agenticai.core.storage.FileStorageException;
import com.agenticai.core.storage.FileStorageService;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

/**
 * Logo upload for the white-label brand mark - the half of the feature that
 * {@code TenantSettingsControllerTest} does not cover, since that one tests
 * setting {@code brandLogoUrl} to a URL the tenant already hosts.
 *
 * <p>Plain unit tests calling the controller methods directly, no
 * MockMvc/web layer - the same convention as the sibling test in this module
 * (see its header note). The two behaviours worth pinning are the
 * entitlement gate (shared with the settings PUT) and, more importantly, the
 * public GET's own gate: it must refuse to serve anything the tenant is not
 * currently publishing, because that endpoint is {@code permitAll}.
 */
class TenantBrandingLogoControllerTest {

    private static final String TENANT_ID = "acme";
    private static final String PUBLIC_BASE = "https://api.example.com";
    private static final String LOGO_URL = PUBLIC_BASE + "/api/share/branding/acme/logo.png";

    private TenantRepository tenantRepository;
    private TenantLimits tenantLimits;
    private FileStorageService fileStorageService;
    private FreeModeService freeModeService;
    private TenantBrandingLogoController controller;
    private Tenant tenant;

    @BeforeEach
    void setUp() {
        tenantRepository = mock(TenantRepository.class);
        tenantLimits = mock(TenantLimits.class);
        fileStorageService = mock(FileStorageService.class);
        freeModeService = mock(FreeModeService.class);
        controller = new TenantBrandingLogoController(
            tenantRepository, tenantLimits, fileStorageService, freeModeService, PUBLIC_BASE);

        tenant = new Tenant(
            "Acme", TenantPlan.UNLIMITED, TENANT_ID, "agentic-ai", AccountType.B2C, ExperienceMode.SIMPLE);
        when(tenantRepository.findBySchemaName(TENANT_ID)).thenReturn(Optional.of(tenant));
        // The settings response the write endpoints return unwraps these.
        when(tenantLimits.maxAgents(TENANT_ID)).thenReturn(Optional.empty());
        when(tenantLimits.maxWorkflows(TENANT_ID)).thenReturn(Optional.empty());
        when(tenantLimits.maxKbCollections(TENANT_ID)).thenReturn(Optional.empty());

        TenantContext.set(TenantContext.of(TENANT_ID, "tenant-admin@acme", Set.of("tenant-admin")));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private static MockMultipartFile png(String name, int bytes) {
        return new MockMultipartFile("file", name, "image/png", new byte[bytes]);
    }

    // ------------------------------------------------------------------
    // Upload: entitlement, format and size.

    @Test
    void uploadWithoutWhiteLabelEntitlementIs403AndStoresNothing() {
        when(tenantLimits.whiteLabel(TENANT_ID)).thenReturn(false);

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
            () -> controller.upload(new MockHttpServletRequest(), png("logo.png", 64)));

        assertEquals(HttpStatus.FORBIDDEN, e.getStatusCode());
        // The gate must come BEFORE the write - a refused upload that still
        // left an object in the bucket would be a silent storage leak.
        verify(fileStorageService, never()).put(any(), any(), any());
        assertNull(tenant.getBrandLogoUrl());
    }

    @Test
    void uploadStoresTheObjectAndPointsBrandLogoUrlAtThePublicEndpoint() {
        when(tenantLimits.whiteLabel(TENANT_ID)).thenReturn(true);

        controller.upload(new MockHttpServletRequest(), png("logo.png", 128));

        verify(fileStorageService).put(eq("tenant-logos/brand-logo.png"), any(), eq("image/png"));
        // The stored value must be exactly what the public GET below serves,
        // and an absolute https URL - the share pages drop anything else
        // client-side (see web/app/agent/[shareId]/api.ts's safeLogoUrl).
        assertEquals(LOGO_URL, tenant.getBrandLogoUrl());
    }

    @Test
    void uploadLeavesTheBrandNameAlone() {
        when(tenantLimits.whiteLabel(TENANT_ID)).thenReturn(true);
        tenant.updateBranding("Acme Support", null);

        controller.upload(new MockHttpServletRequest(), png("logo.png", 128));

        assertEquals("Acme Support", tenant.getBrandDisplayName());
    }

    @Test
    void uploadRejectsSvgEvenThoughItIsAnImage() {
        when(tenantLimits.whiteLabel(TENANT_ID)).thenReturn(true);
        MockMultipartFile svg = new MockMultipartFile(
            "file", "logo.svg", "image/svg+xml",
            "<svg xmlns=\"http://www.w3.org/2000/svg\"/>".getBytes(StandardCharsets.UTF_8));

        // Not a size or a "not an image" failure - SVG is refused on its own
        // terms, because it is served from the API origin to anonymous
        // viewers and can carry scripts.
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> controller.upload(new MockHttpServletRequest(), svg));
        assertTrue(e.getMessage().contains("SVG"), e.getMessage());
        verify(fileStorageService, never()).put(any(), any(), any());
    }

    @Test
    void uploadRejectsANonImageAndAnOversizedFile() {
        when(tenantLimits.whiteLabel(TENANT_ID)).thenReturn(true);

        assertThrows(IllegalArgumentException.class, () -> controller.upload(
            new MockHttpServletRequest(),
            new MockMultipartFile("file", "notes.pdf", "application/pdf", new byte[16])));

        // 2MB cap + 1 byte.
        assertThrows(IllegalArgumentException.class,
            () -> controller.upload(new MockHttpServletRequest(), png("huge.png", 2 * 1024 * 1024 + 1)));

        verify(fileStorageService, never()).put(any(), any(), any());
    }

    @Test
    void changingFormatDeletesThePreviousObjectButReuploadingTheSameFormatDoesNot() {
        when(tenantLimits.whiteLabel(TENANT_ID)).thenReturn(true);

        controller.upload(new MockHttpServletRequest(), png("logo.png", 64));
        // Same format again: the put overwrites the identical key, so a
        // delete here would remove the object just written.
        controller.upload(new MockHttpServletRequest(), png("logo.png", 64));
        verify(fileStorageService, never()).delete(any());

        controller.upload(new MockHttpServletRequest(),
            new MockMultipartFile("file", "logo.webp", "image/webp", new byte[64]));
        verify(fileStorageService).delete("tenant-logos/brand-logo.png");
        assertEquals(PUBLIC_BASE + "/api/share/branding/acme/logo.webp", tenant.getBrandLogoUrl());
    }

    @Test
    void uploadRefusesToMintANonPublicUrlRatherThanStoreOne() {
        // A deployment whose resolved origin is localhost (a misconfigured
        // proxy, or a local run) would otherwise bake a permanently broken,
        // mixed-content URL into a column that public pages render.
        TenantBrandingLogoController local = new TenantBrandingLogoController(
            tenantRepository, tenantLimits, fileStorageService, freeModeService, "http://localhost:8080");
        when(tenantLimits.whiteLabel(TENANT_ID)).thenReturn(true);

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
            () -> local.upload(new MockHttpServletRequest(), png("logo.png", 64)));

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, e.getStatusCode());
        // And it fails BEFORE writing, so there is no orphaned object.
        verify(fileStorageService, never()).put(any(), any(), any());
        assertNull(tenant.getBrandLogoUrl());
    }

    // ------------------------------------------------------------------
    // Remove.

    @Test
    void removeDeletesTheObjectAndClearsTheColumnButKeepsTheBrandName() {
        when(tenantLimits.whiteLabel(TENANT_ID)).thenReturn(true);
        tenant.updateBranding("Acme Support", LOGO_URL);

        controller.remove();

        verify(fileStorageService).delete("tenant-logos/brand-logo.png");
        assertNull(tenant.getBrandLogoUrl());
        assertEquals("Acme Support", tenant.getBrandDisplayName());
    }

    @Test
    void removeClearsAnExternallyHostedUrlWithoutDeletingAnythingFromOurStorage() {
        when(tenantLimits.whiteLabel(TENANT_ID)).thenReturn(true);
        tenant.updateBranding(null, "https://cdn.example.com/logo.png");

        controller.remove();

        // Nothing of theirs is in our bucket, so there is nothing to delete -
        // and deriving a key from a URL we did not mint would be a guess.
        verify(fileStorageService, never()).delete(any());
        assertNull(tenant.getBrandLogoUrl());
    }

    @Test
    void removeOnAnAlreadyClearBrandIsANoOpNotAnError() {
        when(tenantLimits.whiteLabel(TENANT_ID)).thenReturn(true);

        controller.remove();

        verify(fileStorageService, never()).delete(any());
        assertNull(tenant.getBrandLogoUrl());
    }

    // ------------------------------------------------------------------
    // The public GET. This endpoint is permitAll, so every refusal below is
    // load-bearing.

    private void storageHolds(String key, byte[] bytes) {
        when(fileStorageService.get(key)).thenReturn(new ByteArrayInputStream(bytes));
    }

    @Test
    void publicGetServesTheLogoWhenTheTenantIsPublishingExactlyThatUrl() {
        when(tenantLimits.whiteLabel(TENANT_ID)).thenReturn(true);
        tenant.updateBranding(null, LOGO_URL);
        storageHolds("tenant-logos/brand-logo.png", new byte[] {1, 2, 3});

        ResponseEntity<byte[]> res = controller.serve(TENANT_ID, "png");

        assertEquals(HttpStatus.OK, res.getStatusCode());
        assertEquals(MediaType.IMAGE_PNG, res.getHeaders().getContentType());
        assertEquals("nosniff", res.getHeaders().getFirst("X-Content-Type-Options"));
        assertNotNull(res.getBody());
        assertEquals(3, res.getBody().length);
    }

    @Test
    void publicGetLeavesNoTenantContextBehind() {
        // The GET sets TenantContext by hand because there is no JWT on this
        // path. Leaking it would hand the NEXT request served by this thread
        // somebody else's tenant.
        TenantContext.clear();
        when(tenantLimits.whiteLabel(TENANT_ID)).thenReturn(true);
        tenant.updateBranding(null, LOGO_URL);
        storageHolds("tenant-logos/brand-logo.png", new byte[] {1});

        controller.serve(TENANT_ID, "png");

        assertTrue(TenantContext.current().isEmpty());
    }

    @Test
    void publicGetIs404OnceTheEntitlementIsGoneEvenThoughTheColumnStillPointsAtTheObject() {
        // A plan downgrade must restore platform branding instantly, without
        // anyone having to go and clear stored values - the same double gate
        // TenantLimitsServiceImpl#brandLogoUrl already implements.
        when(tenantLimits.whiteLabel(TENANT_ID)).thenReturn(false);
        tenant.updateBranding(null, LOGO_URL);

        assertEquals(HttpStatus.NOT_FOUND, controller.serve(TENANT_ID, "png").getStatusCode());
        verify(fileStorageService, never()).get(any());
    }

    @Test
    void publicGetIs404WhenTheTenantIsNotPublishingThatExactUrl() {
        when(tenantLimits.whiteLabel(TENANT_ID)).thenReturn(true);

        // Brand cleared: the object may still be in the bucket for a moment,
        // but it must stop being served immediately.
        assertEquals(HttpStatus.NOT_FOUND, controller.serve(TENANT_ID, "png").getStatusCode());

        // Publishing a DIFFERENT format than the one requested.
        tenant.updateBranding(null, PUBLIC_BASE + "/api/share/branding/acme/logo.webp");
        assertEquals(HttpStatus.NOT_FOUND, controller.serve(TENANT_ID, "png").getStatusCode());

        // Publishing an externally hosted logo - there is nothing of theirs
        // in our storage to serve.
        tenant.updateBranding(null, "https://cdn.example.com/logo.png");
        assertEquals(HttpStatus.NOT_FOUND, controller.serve(TENANT_ID, "png").getStatusCode());

        verify(fileStorageService, never()).get(any());
    }

    @Test
    void publicGetIs404ForAnUnknownTenantAndAnUnsupportedExtension() {
        when(tenantRepository.findBySchemaName("nope")).thenReturn(Optional.empty());
        assertEquals(HttpStatus.NOT_FOUND, controller.serve("nope", "png").getStatusCode());

        when(tenantLimits.whiteLabel(TENANT_ID)).thenReturn(true);
        tenant.updateBranding(null, LOGO_URL);
        // svg is not in ALLOWED_FORMATS, so it is refused before any lookup.
        assertEquals(HttpStatus.NOT_FOUND, controller.serve(TENANT_ID, "svg").getStatusCode());
        verify(fileStorageService, never()).get(any());
    }

    @Test
    void publicGetIs404RatherThan500WhenTheObjectHasGoneMissing() {
        // Cleared first, like the real anonymous request (no JWT, so no
        // TenantContextFilter has run) - the point of the trailing assertion
        // is that the failure path still unwinds the context it set.
        TenantContext.clear();
        when(tenantLimits.whiteLabel(TENANT_ID)).thenReturn(true);
        tenant.updateBranding(null, LOGO_URL);
        when(fileStorageService.get("tenant-logos/brand-logo.png"))
            .thenThrow(new FileStorageException("gone", new java.io.IOException("missing")));

        // The share page falls back to the brand NAME, which is the correct
        // degraded state - not a 5xx on somebody's public page.
        assertEquals(HttpStatus.NOT_FOUND, controller.serve(TENANT_ID, "png").getStatusCode());
        assertTrue(TenantContext.current().isEmpty());
    }
}
