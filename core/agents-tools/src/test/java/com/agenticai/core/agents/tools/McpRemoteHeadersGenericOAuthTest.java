package com.agenticai.core.agents.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.mcp.McpReviewStatus;
import com.agenticai.core.mcp.McpServerType;
import com.agenticai.core.mcp.atlassian.AtlassianOAuthService;
import com.agenticai.core.mcp.dto.McpDynamicConfig;
import com.agenticai.core.mcp.oauth.McpOAuthException;
import com.agenticai.core.mcp.oauth.McpOAuthService;
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
 * A registration stamped with the generic remote-MCP OAuth marker ({@link McpOAuthService#MARKER})
 * gets its bearer from the token source through the same per-request customizer as the provider and
 * Atlassian flows.
 */
class McpRemoteHeadersGenericOAuthTest {

    private static final String URL = "https://mcp.example.com/mcp";
    private static final String MARKER = McpOAuthService.MARKER;
    private static final TenantContext TENANT = TenantContext.of("tenant_a", "user-1", Set.of());

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    private static McpDynamicConfig config(String marker, Map<String, String> env) {
        return new McpDynamicConfig(
                UUID.randomUUID(),
                "remote",
                McpServerType.STREAMABLE_HTTP,
                null,
                null,
                env,
                URL,
                false,
                McpReviewStatus.NOT_REQUIRED,
                null,
                marker);
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
    void theGenericMarkerIsOAuthAndStaticEnvIsIgnored() {
        assertTrue(McpRemoteHeaders.isOAuth(config(MARKER, Map.of())));
        assertTrue(McpRemoteHeaders.isProviderOAuth(config(MARKER, Map.of())));
        assertEquals(
                Map.of(),
                McpRemoteHeaders.staticHeaders(
                        config(MARKER, Map.of("X-Api-Key", "must-not-be-sent"))));
        assertFalse(
                McpRemoteHeaders.isOAuth(config(null, Map.of())),
                "no marker: byte-identical to before");
        assertFalse(
                McpRemoteHeaders.isOAuth(config("oauth-remote-x", Map.of())),
                "only the exact marker");
    }

    @Test
    void theBearerComesFromTheSourceForTheRegistrationUrlOnEveryRequest() {
        McpOAuthTokenSource src = source();
        when(src.accessTokenFor(MARKER, URL)).thenReturn("t-connect", "t-req1", "t-req2");
        TenantContext.set(TENANT);
        McpRemoteHeaders.PerRequestHeaders headers =
                new McpRemoteHeaders.PerRequestHeaders(
                        config(MARKER, Map.of()),
                        mock(AtlassianOAuthService.class),
                        () -> null,
                        TENANT,
                        src);
        TenantContext.clear();

        assertEquals(Optional.of("Bearer t-req1"), authorization(headers));
        assertEquals(Optional.of("Bearer t-req2"), authorization(headers));
    }

    @Test
    void aTransientRefreshFailureSendsTheLastKnownToken() {
        McpOAuthTokenSource src = source();
        when(src.accessTokenFor(MARKER, URL))
                .thenReturn("t-connect")
                .thenThrow(new McpOAuthException("AS down", null, null));
        McpRemoteHeaders.PerRequestHeaders headers =
                new McpRemoteHeaders.PerRequestHeaders(
                        config(MARKER, Map.of()),
                        mock(AtlassianOAuthService.class),
                        () -> null,
                        TENANT,
                        src);
        assertEquals(Optional.of("Bearer t-connect"), authorization(headers));
    }

    @Test
    void aPermanentlyRejectedConnectionSendsNoStaleBearerSoTheServers401Surfaces() {
        McpOAuthTokenSource src = source();
        when(src.accessTokenFor(MARKER, URL))
                .thenReturn("t-connect")
                .thenThrow(new McpOAuthException("needs reauth", null, "reauth_required"));
        McpRemoteHeaders.PerRequestHeaders headers =
                new McpRemoteHeaders.PerRequestHeaders(
                        config(MARKER, Map.of()),
                        mock(AtlassianOAuthService.class),
                        () -> null,
                        TENANT,
                        src);
        assertEquals(Optional.empty(), authorization(headers));
        assertEquals(
                Optional.empty(),
                authorization(headers),
                "stays empty, never resurrects the stale token");
    }

    @Test
    void connectingBeforeSignInFailsLoudlyWithTheNotConnectedDiagnosis() {
        McpOAuthTokenSource src = source();
        when(src.accessTokenFor(MARKER, URL))
                .thenThrow(new McpOAuthException("Not connected", null, "not_connected"));
        assertThrows(
                McpOAuthException.class,
                () ->
                        new McpRemoteHeaders.PerRequestHeaders(
                                config(MARKER, Map.of()),
                                mock(AtlassianOAuthService.class),
                                () -> null,
                                TENANT,
                                src));
    }
}
