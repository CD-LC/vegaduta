package com.agenticai.core.agents.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.mcp.McpReviewStatus;
import com.agenticai.core.mcp.McpServerType;
import com.agenticai.core.mcp.atlassian.AtlassianOAuthService;
import com.agenticai.core.mcp.dto.McpDynamicConfig;
import com.agenticai.core.mcp.oauth.McpOAuthTokenSource;
import com.agenticai.core.security.TenantContext;
import java.net.URI;
import java.net.http.HttpRequest;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The provider-registry generalisation of the Atlassian bearer injection: a registration whose
 * marker is {@code oauth:<id>} gets its token from the {@link McpOAuthTokenSource}, through the
 * SAME per-request customizer.
 */
class McpRemoteHeadersProviderOAuthTest {

    private static final String URL = "https://api.githubcopilot.com/mcp/";
    private static final String MARKER = "oauth:github";
    private static final TenantContext TENANT = TenantContext.of("tenant_a", "user-1", Set.of());

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    private static McpDynamicConfig providerConfig(Map<String, String> env) {
        return new McpDynamicConfig(
                UUID.randomUUID(),
                "github-oauth",
                McpServerType.STREAMABLE_HTTP,
                null,
                null,
                env,
                URL,
                false,
                McpReviewStatus.NOT_REQUIRED,
                null,
                MARKER);
    }

    private static McpOAuthTokenSource source() {
        McpOAuthTokenSource s = mock(McpOAuthTokenSource.class);
        when(s.handles(MARKER)).thenReturn(true);
        return s;
    }

    private static Optional<String> authorization(McpRemoteHeaders.PerRequestHeaders headers) {
        HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(URL));
        headers.apply(rb);
        return rb.build().headers().firstValue("Authorization");
    }

    @Test
    void aProviderMarkerIsOAuthButOnlyAtlassianAndOurPrefixAre() {
        assertTrue(McpRemoteHeaders.isOAuth(providerConfig(Map.of())));
        assertTrue(McpRemoteHeaders.isProviderOAuth(providerConfig(Map.of())));
        McpDynamicConfig plain =
                new McpDynamicConfig(
                        UUID.randomUUID(),
                        "x",
                        McpServerType.STREAMABLE_HTTP,
                        null,
                        null,
                        Map.of(),
                        URL,
                        false,
                        McpReviewStatus.NOT_REQUIRED,
                        null,
                        "SOMETHING_ELSE");
        assertFalse(
                McpRemoteHeaders.isOAuth(plain), "an unknown marker must not be treated as OAuth");
    }

    @Test
    void theBearerIsMintedFromTheProviderSourceForTheRegistrationUrlOnEveryRequest() {
        McpOAuthTokenSource src = source();
        when(src.accessTokenFor(MARKER, URL)).thenReturn("t-connect", "t-req1", "t-req2");
        TenantContext.set(TENANT);
        McpRemoteHeaders.PerRequestHeaders headers =
                new McpRemoteHeaders.PerRequestHeaders(
                        providerConfig(Map.of()),
                        mock(AtlassianOAuthService.class),
                        () -> null,
                        TENANT,
                        src);
        TenantContext.clear();

        assertEquals(Optional.of("Bearer t-req1"), authorization(headers));
        assertEquals(Optional.of("Bearer t-req2"), authorization(headers));
    }

    @Test
    void theTokenFetchRunsUnderTheCapturedTenantBecauseTheTransportThreadHasNone() {
        McpOAuthTokenSource src = source();
        java.util.List<String> tenants = new java.util.ArrayList<>();
        when(src.accessTokenFor(MARKER, URL))
                .thenAnswer(
                        inv -> {
                            tenants.add(
                                    TenantContext.current()
                                            .map(TenantContext::getTenantId)
                                            .orElse("<none>"));
                            return "tok";
                        });
        TenantContext.set(TENANT);
        McpRemoteHeaders.PerRequestHeaders headers =
                new McpRemoteHeaders.PerRequestHeaders(
                        providerConfig(Map.of()),
                        mock(AtlassianOAuthService.class),
                        () -> null,
                        TENANT,
                        src);
        TenantContext.clear();

        authorization(headers);

        assertEquals(java.util.List.of("tenant_a", "tenant_a"), tenants);
        assertTrue(TenantContext.current().isEmpty());
    }

    @Test
    void aFailedRefreshSendsTheLastKnownTokenAndTheInitialFailurePropagates() {
        McpOAuthTokenSource ok = source();
        when(ok.accessTokenFor(MARKER, URL))
                .thenReturn("t-connect")
                .thenThrow(new IllegalStateException("provider down"));
        McpRemoteHeaders.PerRequestHeaders headers =
                new McpRemoteHeaders.PerRequestHeaders(
                        providerConfig(Map.of()),
                        mock(AtlassianOAuthService.class),
                        () -> null,
                        TENANT,
                        ok);
        assertEquals(Optional.of("Bearer t-connect"), authorization(headers));

        McpOAuthTokenSource broken = source();
        when(broken.accessTokenFor(MARKER, URL))
                .thenThrow(new IllegalStateException("not connected"));
        assertThrows(
                IllegalStateException.class,
                () ->
                        new McpRemoteHeaders.PerRequestHeaders(
                                providerConfig(Map.of()),
                                mock(AtlassianOAuthService.class),
                                () -> null,
                                TENANT,
                                broken));
    }

    @Test
    void withoutATokenSourceAProviderRegistrationFailsLoudlyInsteadOfSendingNoCredential() {
        assertThrows(
                IllegalStateException.class,
                () ->
                        new McpRemoteHeaders.PerRequestHeaders(
                                providerConfig(Map.of()),
                                mock(AtlassianOAuthService.class),
                                () -> null,
                                TENANT));
        assertThrows(
                IllegalStateException.class,
                () ->
                        McpRemoteHeaders.build(
                                providerConfig(Map.of()), mock(AtlassianOAuthService.class)));
    }

    @Test
    void aSourceThatDoesNotOwnTheMarkerIsNotAskedForAToken() {
        McpOAuthTokenSource foreign = mock(McpOAuthTokenSource.class);
        when(foreign.handles(MARKER)).thenReturn(false);

        assertThrows(
                IllegalStateException.class,
                () ->
                        McpRemoteHeaders.build(
                                providerConfig(Map.of()),
                                mock(AtlassianOAuthService.class),
                                foreign));
        verify(foreign, never()).accessTokenFor(anyString(), anyString());
    }

    @Test
    void theSnapshotBuildUsedByTheProbeCarriesTheProviderBearerAndIgnoresStaticEnv() {
        McpOAuthTokenSource src = source();
        when(src.accessTokenFor(MARKER, URL)).thenReturn("probe-token");

        Map<String, String> headers =
                McpRemoteHeaders.build(
                        providerConfig(Map.of("X-Api-Key", "must-not-be-sent")),
                        mock(AtlassianOAuthService.class),
                        src);

        assertEquals(Map.of("Authorization", "Bearer probe-token"), headers);
        assertEquals(
                Map.of(), McpRemoteHeaders.staticHeaders(providerConfig(Map.of("X-Api-Key", "k"))));
    }

    @Test
    void atlassianStillUsesItsOwnServiceAndNeverTheProviderSource() {
        AtlassianOAuthService atlassian = mock(AtlassianOAuthService.class);
        when(atlassian.currentAccessToken()).thenReturn("atl-token");
        McpOAuthTokenSource src = mock(McpOAuthTokenSource.class);
        McpDynamicConfig config =
                new McpDynamicConfig(
                        UUID.randomUUID(),
                        "atlassian",
                        McpServerType.STREAMABLE_HTTP,
                        null,
                        null,
                        Map.of(),
                        "https://mcp.atlassian.com/v1/mcp/authv2",
                        false,
                        McpReviewStatus.NOT_REQUIRED,
                        null,
                        "ATLASSIAN");

        assertEquals(
                Map.of("Authorization", "Bearer atl-token"),
                McpRemoteHeaders.build(config, atlassian, src));
        verify(src, never()).accessTokenFor(anyString(), anyString());
    }
}
