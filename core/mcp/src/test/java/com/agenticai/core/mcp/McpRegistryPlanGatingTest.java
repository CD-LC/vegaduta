package com.agenticai.core.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.mcp.dto.McpServerRegistrationRequest;
import com.agenticai.core.mcp.dto.McpServerRegistrationResponse;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantLimitExceededException;
import com.agenticai.core.security.TenantPlan;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * {@link McpRegistryService#register} plan-gating (2026-07-27 billing audit fix) and the soft
 * missing-credential warning for catalog servers.
 */
class McpRegistryPlanGatingTest extends McpRegistryServiceFixture {

    // --- register(): plan-gating (2026-07-27 billing audit fix) ---------------

    @Test
    void registerAllowsAThirdFreeTenantConnectorNoFurther() {
        setTenant("free-tenant", TenantPlan.FREE);
        when(repository.count()).thenReturn(2L); // already at the FREE cap
        when(repository.findByName("filesystem")).thenReturn(Optional.empty());
        McpServerRegistrationRequest request =
            new McpServerRegistrationRequest("filesystem", McpServerType.STDIO, "desc", true);

        TenantLimitExceededException ex = assertThrows(
            TenantLimitExceededException.class, () -> service.register(request));
        assertTrue(ex.getMessage().contains("2 MCP connectors"));
        verify(repository, never()).save(any());
    }

    @Test
    void registerAllowsAFreeTenantUnderTheCapToRegisterACatalogConnector() {
        setTenant("free-tenant", TenantPlan.FREE);
        when(repository.count()).thenReturn(1L); // one below the FREE cap of 2
        when(repository.findByName("filesystem")).thenReturn(Optional.empty());
        McpServerRegistrationRequest request =
            new McpServerRegistrationRequest("filesystem", McpServerType.STDIO, "desc", true);

        McpServerRegistrationResponse response = service.register(request);

        assertEquals("filesystem", response.name());
    }

    @Test
    void registerRejectsAFreeTenantRegisteringANonCatalogCustomServer() {
        setTenant("free-tenant", TenantPlan.FREE);
        when(repository.count()).thenReturn(0L);
        when(repository.findByName("my-custom-server")).thenReturn(Optional.empty());
        McpServerRegistrationRequest request = new McpServerRegistrationRequest(
            "my-custom-server", McpServerType.STDIO, "desc", true,
            "npx", List.of("-y", "some-arbitrary-package"), Map.of());

        TenantLimitExceededException ex = assertThrows(
            TenantLimitExceededException.class, () -> service.register(request));
        assertTrue(ex.getMessage().contains("built-in MCP catalog"));
        verify(repository, never()).save(any());
    }

    @Test
    void registerAllowsAPremiumTenantToRegisterACustomServerUpToItsOwnCap() {
        setTenant("premium-tenant", TenantPlan.PREMIUM);
        when(repository.count()).thenReturn(9L); // one below the PREMIUM cap of 10
        when(repository.findByName("my-custom-server")).thenReturn(Optional.empty());
        McpServerRegistrationRequest request = new McpServerRegistrationRequest(
            "my-custom-server", McpServerType.STDIO, "desc", true,
            "npx", List.of("-y", "some-arbitrary-package"), Map.of());

        McpServerRegistrationResponse response = service.register(request);

        assertEquals("my-custom-server", response.name());
    }

    @Test
    void registerRejectsAPremiumTenantAtItsOwnCap() {
        setTenant("premium-tenant", TenantPlan.PREMIUM);
        when(repository.count()).thenReturn(10L);
        when(repository.findByName("my-custom-server")).thenReturn(Optional.empty());
        McpServerRegistrationRequest request = new McpServerRegistrationRequest(
            "my-custom-server", McpServerType.STDIO, "desc", true,
            "npx", List.of("-y", "some-arbitrary-package"), Map.of());

        assertThrows(TenantLimitExceededException.class, () -> service.register(request));
    }

    @Test
    void registerNeverCapsAnUnlimitedTenant() {
        setTenant("unlimited-tenant", TenantPlan.UNLIMITED);
        when(repository.count()).thenReturn(500L);
        when(repository.findByName("my-custom-server")).thenReturn(Optional.empty());
        McpServerRegistrationRequest request = new McpServerRegistrationRequest(
            "my-custom-server", McpServerType.STDIO, "desc", true,
            "npx", List.of("-y", "some-arbitrary-package"), Map.of());

        McpServerRegistrationResponse response = service.register(request);

        assertEquals("my-custom-server", response.name());
    }

    @Test
    void registerNeverRecapsAnUpdateToAnExistingRegistration() {
        // The FREE tenant is already AT the cap, but this call only toggles
        // an EXISTING row (upsert-by-name) - must not be blocked, same
        // "gate create, not update" posture as AgentService.
        setTenant("free-tenant", TenantPlan.FREE);
        when(repository.count()).thenReturn(2L);
        McpServerRegistration existing = new McpServerRegistration("filesystem", McpServerType.STDIO, "desc", false);
        when(repository.findByName("filesystem")).thenReturn(Optional.of(existing));
        McpServerRegistrationRequest request =
            new McpServerRegistrationRequest("filesystem", McpServerType.STDIO, "desc", true);

        McpServerRegistrationResponse response = service.register(request);

        assertTrue(response.enabled());
    }

    @Test
    void registerDoesNotCapWhenThereIsNoTenantContext() {
        // No TenantContext.set() call - same "no request context, not
        // capped" posture as AgentService#create's own tenantId-null branch.
        when(repository.count()).thenReturn(1_000L);
        when(repository.findByName("my-custom-server")).thenReturn(Optional.empty());
        McpServerRegistrationRequest request = new McpServerRegistrationRequest(
            "my-custom-server", McpServerType.STDIO, "desc", true,
            "npx", List.of("-y", "some-arbitrary-package"), Map.of());

        McpServerRegistrationResponse response = service.register(request);

        assertEquals("my-custom-server", response.name());
    }

    // --- register(): soft validation warning for a missing catalog env var -----

    @Test
    void registerWarnsWhenACatalogSourcedServerIsMissingARequiredCredentialButDoesNotBlock() {
        when(repository.findByName("brave-search")).thenReturn(Optional.empty());
        McpServerRegistrationRequest request = new McpServerRegistrationRequest(
            "brave-search", McpServerType.STDIO, "desc", true,
            "npx", List.of("-y", "@modelcontextprotocol/server-brave-search"), Map.of());

        McpServerRegistrationResponse response = service.register(request);

        // Never blocks - the registration still succeeds.
        assertEquals("brave-search", response.name());
        assertTrue(response.warnings().stream().anyMatch(w -> w.contains("BRAVE_API_KEY")));
    }

    @Test
    void registerAddsNoMissingCredentialWarningWhenEveryRequiredVarIsPresent() {
        when(repository.findByName("brave-search")).thenReturn(Optional.empty());
        when(encryptionService.encrypt(anyString())).thenReturn("enc");
        McpServerRegistrationRequest request = new McpServerRegistrationRequest(
            "brave-search", McpServerType.STDIO, "desc", true,
            "npx", List.of("-y", "@modelcontextprotocol/server-brave-search"), Map.of("BRAVE_API_KEY", "real-key"));

        McpServerRegistrationResponse response = service.register(request);

        assertTrue(response.warnings().isEmpty());
    }

    @Test
    void registerAddsNoMissingCredentialWarningForANonCatalogCustomServer() {
        // No known required-env schema to check a hand-typed command against
        // - see missingRequiredEnvWarnings' javadoc for this deliberate,
        // stated limitation.
        when(repository.findByName("my-custom-server")).thenReturn(Optional.empty());
        McpServerRegistrationRequest request = new McpServerRegistrationRequest(
            "my-custom-server", McpServerType.STDIO, "desc", true,
            "npx", List.of("-y", "some-arbitrary-package"), Map.of());

        McpServerRegistrationResponse response = service.register(request);

        assertTrue(response.warnings().isEmpty());
    }
}
