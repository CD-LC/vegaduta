package com.agenticai.core.agents.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.attestation.AgentAttestationService;
import com.agenticai.core.mcp.McpReviewStatus;
import com.agenticai.core.mcp.McpServerType;
import com.agenticai.core.mcp.atlassian.AtlassianOAuthService;
import com.agenticai.core.mcp.dto.McpDynamicConfig;
import com.agenticai.core.security.TenantContext;
import java.net.URI;
import java.net.http.HttpRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * {@link McpRemoteHeaders.PerRequestHeaders} - the per-request header customizer the remote MCP
 * transports use since 2026-09-28 (the old {@code customizeRequest} seam ran once at build time in
 * SDK 0.18.2, freezing the OAuth bearer and the attestation JWT for the connection's life).
 */
class McpRemoteHeadersTest {

    private static final URI ENDPOINT = URI.create("https://mcp.example.com/mcp");

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    private static McpDynamicConfig oauthConfig() {
        return new McpDynamicConfig(
                UUID.randomUUID(),
                "atlassian",
                McpServerType.STREAMABLE_HTTP,
                null,
                null,
                Map.of(),
                "https://mcp.atlassian.com/v1/mcp",
                false,
                McpReviewStatus.NOT_REQUIRED,
                null,
                "ATLASSIAN");
    }

    private static McpDynamicConfig staticConfig(Map<String, String> env) {
        return new McpDynamicConfig(
                UUID.randomUUID(),
                "remote",
                McpServerType.STREAMABLE_HTTP,
                null,
                List.of(),
                env,
                ENDPOINT.toString());
    }

    private static Optional<String> headerAfterApply(
            McpRemoteHeaders.PerRequestHeaders headers, String name) {
        HttpRequest.Builder rb = HttpRequest.newBuilder(ENDPOINT);
        headers.apply(rb);
        return rb.build().headers().firstValue(name);
    }

    private static final TenantContext TENANT_A = TenantContext.of("tenant_a", "user-1", Set.of());

    @Test
    void oauthBearerIsFetchedFreshOnEveryRequestNotFrozenAtConnectTime() {
        AtlassianOAuthService oauth = mock(AtlassianOAuthService.class);
        when(oauth.currentAccessToken()).thenReturn("t-connect", "t-req1", "t-req2");
        McpRemoteHeaders.PerRequestHeaders headers =
                new McpRemoteHeaders.PerRequestHeaders(oauthConfig(), oauth, () -> null, TENANT_A);

        assertEquals(Optional.of("Bearer t-req1"), headerAfterApply(headers, "Authorization"));
        assertEquals(Optional.of("Bearer t-req2"), headerAfterApply(headers, "Authorization"));
    }

    @Test
    void failedPerRequestRefreshSendsTheLastKnownTokenInsteadOfFailing() {
        AtlassianOAuthService oauth = mock(AtlassianOAuthService.class);
        when(oauth.currentAccessToken())
                .thenReturn("t-connect")
                .thenThrow(new IllegalStateException("token endpoint down"));
        McpRemoteHeaders.PerRequestHeaders headers =
                new McpRemoteHeaders.PerRequestHeaders(oauthConfig(), oauth, () -> null, TENANT_A);

        assertEquals(Optional.of("Bearer t-connect"), headerAfterApply(headers, "Authorization"));
    }

    @Test
    void initialTokenFetchFailureStillPropagatesSoConnectRecordsADiagnosis() {
        AtlassianOAuthService oauth = mock(AtlassianOAuthService.class);
        when(oauth.currentAccessToken()).thenThrow(new IllegalStateException("not connected"));

        assertThrows(
                IllegalStateException.class,
                () ->
                        new McpRemoteHeaders.PerRequestHeaders(
                                oauthConfig(), oauth, () -> null, TENANT_A));
    }

    @Test
    void tokenRefreshRunsUnderTheCapturedTenantOnAContextlessTransportThread() {
        AtlassianOAuthService oauth = mock(AtlassianOAuthService.class);
        List<String> tenantsSeen = new ArrayList<>();
        when(oauth.currentAccessToken())
                .thenAnswer(
                        inv -> {
                            tenantsSeen.add(
                                    TenantContext.current()
                                            .map(TenantContext::getTenantId)
                                            .orElse("<none>"));
                            return "tok";
                        });
        TenantContext.set(TENANT_A);
        McpRemoteHeaders.PerRequestHeaders headers =
                new McpRemoteHeaders.PerRequestHeaders(oauthConfig(), oauth, () -> null, TENANT_A);
        TenantContext.clear(); // the SDK's transport thread has no TenantContext

        headerAfterApply(headers, "Authorization");

        assertEquals(List.of("tenant_a", "tenant_a"), tenantsSeen);
        assertTrue(
                TenantContext.current().isEmpty(),
                "the captured tenant must not leak onto the transport thread");
    }

    @Test
    void staticEnvHeadersAreAppliedOnEveryRequest() {
        McpRemoteHeaders.PerRequestHeaders headers =
                new McpRemoteHeaders.PerRequestHeaders(
                        staticConfig(Map.of("X-Api-Key", "k1", "bad header", "skipped")),
                        mock(AtlassianOAuthService.class),
                        () -> null,
                        TENANT_A);

        assertEquals(Optional.of("k1"), headerAfterApply(headers, "X-Api-Key"));
        assertEquals(Optional.of("k1"), headerAfterApply(headers, "X-Api-Key"));
    }

    @Test
    void attestationIsMintedPerRequestForTheCapturedTenantAndSubject() {
        AgentAttestationService attestation = mock(AgentAttestationService.class);
        when(attestation.mint("tenant_a", "user-1", null)).thenReturn(Optional.of("jwt-1"));
        McpRemoteHeaders.PerRequestHeaders headers =
                new McpRemoteHeaders.PerRequestHeaders(
                        staticConfig(Map.of()),
                        mock(AtlassianOAuthService.class),
                        () -> attestation,
                        TENANT_A);

        assertEquals(
                Optional.of("jwt-1"),
                headerAfterApply(headers, AgentAttestationService.HEADER_NAME));
    }

    @Test
    void attestationIsSkippedRatherThanMintedWithAnEmptySubjectWhenNoTenantResolves() {
        AgentAttestationService attestation = mock(AgentAttestationService.class);
        McpRemoteHeaders.PerRequestHeaders headers =
                new McpRemoteHeaders.PerRequestHeaders(
                        staticConfig(Map.of()),
                        mock(AtlassianOAuthService.class),
                        () -> attestation,
                        null);

        assertEquals(
                Optional.empty(), headerAfterApply(headers, AgentAttestationService.HEADER_NAME));
        verify(attestation, never()).mint(any(), any(), any());
    }

    @Test
    void attestationNeverOverwritesACallerConfiguredHeader() {
        AgentAttestationService attestation = mock(AgentAttestationService.class);
        McpRemoteHeaders.PerRequestHeaders headers =
                new McpRemoteHeaders.PerRequestHeaders(
                        staticConfig(Map.of(AgentAttestationService.HEADER_NAME, "caller-set")),
                        mock(AtlassianOAuthService.class),
                        () -> attestation,
                        TENANT_A);

        assertEquals(
                Optional.of("caller-set"),
                headerAfterApply(headers, AgentAttestationService.HEADER_NAME));
        verify(attestation, never()).mint(anyString(), any(), any());
    }
}
