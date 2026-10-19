package com.agenticai.core.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.mcp.dto.McpServerRegistrationRequest;
import com.agenticai.core.mcp.dto.McpServerRegistrationResponse;
import com.agenticai.core.security.TenantPlan;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** {@link McpRegistryService#register}: MCP-STDIO-SANDBOXING review-gate assignment. */
class McpRegistryReviewGateTest extends McpRegistryServiceFixture {

    // --- register(): MCP-STDIO-SANDBOXING review gate assignment ---------------

    @Test
    void registerAssignsNotRequiredToACatalogSourcedStdioServer() {
        when(repository.findByName("brave-search")).thenReturn(Optional.empty());
        when(encryptionService.encrypt(anyString())).thenReturn("enc");
        McpServerRegistrationRequest request = new McpServerRegistrationRequest(
            "brave-search", McpServerType.STDIO, "desc", true,
            "npx", List.of("-y", "@modelcontextprotocol/server-brave-search"), Map.of("BRAVE_API_KEY", "k"));

        McpServerRegistrationResponse response = service.register(request);

        assertEquals(com.agenticai.core.mcp.McpReviewStatus.NOT_REQUIRED, response.reviewStatus());
    }

    @Test
    void registerAssignsPendingToAHandTypedCustomStdioServer() {
        when(repository.findByName("my-custom-server")).thenReturn(Optional.empty());
        McpServerRegistrationRequest request = new McpServerRegistrationRequest(
            "my-custom-server", McpServerType.STDIO, "desc", true,
            "npx", List.of("-y", "some-arbitrary-package"), Map.of());

        McpServerRegistrationResponse response = service.register(request);

        assertEquals(com.agenticai.core.mcp.McpReviewStatus.PENDING, response.reviewStatus());
    }

    @Test
    void registerAssignsNotRequiredToARemoteServerRegardlessOfName() {
        when(repository.findByName("my-custom-remote")).thenReturn(Optional.empty());
        McpServerRegistrationRequest request = new McpServerRegistrationRequest(
            "my-custom-remote", McpServerType.STREAMABLE_HTTP, "desc", true,
            null, null, Map.of(), "https://mcp.example.com/mcp");

        McpServerRegistrationResponse response = service.register(request);

        assertEquals(com.agenticai.core.mcp.McpReviewStatus.NOT_REQUIRED, response.reviewStatus());
    }

    @Test
    void registerRecomputesReviewStatusOnEveryResaveEvenIfItWasAlreadyApproved() {
        // An APPROVED custom server whose command is re-submitted must go
        // back to PENDING - an approval was only ever for the PREVIOUS
        // command/args/env, same "any config change invalidates the last
        // verification" posture usageStatus already takes.
        McpServerRegistration existing = new McpServerRegistration("my-custom-server", McpServerType.STDIO, "d", true);
        existing.configureDynamic("npx", "[]", null);
        existing.approveReview();
        when(repository.findByName("my-custom-server")).thenReturn(Optional.of(existing));
        McpServerRegistrationRequest request = new McpServerRegistrationRequest(
            "my-custom-server", McpServerType.STDIO, "desc", true,
            "npx", List.of("-y", "some-other-package"), Map.of());

        McpServerRegistrationResponse response = service.register(request);

        assertEquals(com.agenticai.core.mcp.McpReviewStatus.PENDING, response.reviewStatus());
    }

    @Test
    void registerCapturesTheRequestingSubjectOnlyAtCreationTime() {
        setTenant("free-tenant", TenantPlan.FREE);
        // FREE plan needs a catalog entry to pass enforcePlanLimitsForNewConnector.
        when(repository.count()).thenReturn(0L);
        when(repository.findByName("filesystem")).thenReturn(Optional.empty());
        McpServerRegistrationRequest request =
            new McpServerRegistrationRequest("filesystem", McpServerType.STDIO, "desc", true);

        service.register(request);

        org.mockito.ArgumentCaptor<McpServerRegistration> captor = org.mockito.ArgumentCaptor.forClass(McpServerRegistration.class);
        verify(repository).save(captor.capture());
        assertEquals("test-subject", captor.getValue().getRequestedBy());
    }
}
