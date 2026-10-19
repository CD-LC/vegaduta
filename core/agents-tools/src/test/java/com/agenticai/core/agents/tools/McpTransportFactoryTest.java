package com.agenticai.core.agents.tools;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.mcp.McpCatalogService;
import com.agenticai.core.mcp.McpConnectionPhase;
import com.agenticai.core.mcp.McpConnectionStatus;
import com.agenticai.core.mcp.McpRegistryService;
import com.agenticai.core.mcp.McpRemoteUrlGuard;
import com.agenticai.core.mcp.McpReviewStatus;
import com.agenticai.core.mcp.McpServerType;
import com.agenticai.core.mcp.McpStdioCommandGuard;
import com.agenticai.core.mcp.McpUsageStatus;
import com.agenticai.core.mcp.atlassian.AtlassianOAuthService;
import com.agenticai.core.mcp.dto.McpDynamicConfig;
import com.agenticai.core.mcp.dto.McpServerRegistrationResponse;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.tenancy.TenantSchemaIterator;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.spec.McpClientTransport;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.http.HttpClient;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;

/** Transport construction: env-isolated stdio, redirect-never remote transports, per-request headers. */
class McpTransportFactoryTest extends DynamicMcpConnectionManagerTestBase {

    @Test
    void constructStdioTransportBuildsTheEnvIsolatedSubclassNotARawSdkTransport() {
        DynamicMcpConnectionManager manager =
                newManager(mock(McpRegistryService.class), mock(TenantSchemaIterator.class));
        ServerParameters params = ServerParameters.builder("echo").build();

        McpClientTransport transport = manager.constructStdioTransport(params);

        // Deliberately getClass(), not instanceof: a raw SDK StdioClientTransport
        // would also satisfy instanceof and silently defeat this regression test.
        assertEquals(EnvIsolatedStdioClientTransport.class, transport.getClass());
    }

    @Test
    void buildRemoteTransportSetsRedirectNeverOnTheStreamableHttpTransportsHttpClient()
            throws ReflectiveOperationException {
        McpRegistryService registryService = mock(McpRegistryService.class);
        TenantSchemaIterator tenantSchemaIterator = mock(TenantSchemaIterator.class);
        DynamicMcpConnectionManager manager = newManager(registryService, tenantSchemaIterator);
        McpDynamicConfig config =
                new McpDynamicConfig(
                        UUID.randomUUID(),
                        "public-remote",
                        McpServerType.STREAMABLE_HTTP,
                        null,
                        List.of(),
                        Map.of(),
                        "https://mcp.example.com/mcp");

        McpClientTransport transport = manager.buildRemoteTransport(config);

        HttpClient httpClient = extractHttpClient(transport);
        assertEquals(
                HttpClient.Redirect.NEVER,
                httpClient.followRedirects(),
                "a guard-passing public URL that 302s to an internal target must not be followed un-reguarded (pentest M4)");
    }

    @Test
    void buildRemoteTransportSetsRedirectNeverOnTheSseTransportsHttpClient()
            throws ReflectiveOperationException {
        McpRegistryService registryService = mock(McpRegistryService.class);
        TenantSchemaIterator tenantSchemaIterator = mock(TenantSchemaIterator.class);
        DynamicMcpConnectionManager manager = newManager(registryService, tenantSchemaIterator);
        McpDynamicConfig config =
                new McpDynamicConfig(
                        UUID.randomUUID(),
                        "public-remote-sse",
                        McpServerType.SSE,
                        null,
                        List.of(),
                        Map.of(),
                        "https://mcp.example.com/sse");

        McpClientTransport transport = manager.buildRemoteTransport(config);

        HttpClient httpClient = extractHttpClient(transport);
        assertEquals(HttpClient.Redirect.NEVER, httpClient.followRedirects());
    }

    // --- Pentest H2: connect()/test() must use the DNS-resolving guard, not the ------
    // --- string-only one, at both re-check points -------------------------------------

    @Test
    void
            remoteHeadersReturnsALiveBearerTokenForAnAtlassianOauthProviderRegistrationInsteadOfTheStaticEnv()
                    throws ReflectiveOperationException {
        com.agenticai.core.mcp.atlassian.AtlassianOAuthService atlassianOAuthService =
                mock(com.agenticai.core.mcp.atlassian.AtlassianOAuthService.class);
        when(atlassianOAuthService.currentAccessToken()).thenReturn("live-access-token");
        DynamicMcpConnectionManager manager =
                newManager(
                        mock(McpRegistryService.class),
                        mock(TenantSchemaIterator.class),
                        noAgentToolProvider(),
                        atlassianOAuthService);
        // env carries a stale/irrelevant static value - must be ignored
        // entirely once oauthProvider is set, not merged with the live token.
        McpDynamicConfig config =
                new McpDynamicConfig(
                        UUID.randomUUID(),
                        "atlassian",
                        McpServerType.STREAMABLE_HTTP,
                        null,
                        null,
                        Map.of("Authorization", "Bearer stale-static-token"),
                        "https://mcp.atlassian.com/v1/mcp",
                        false,
                        McpReviewStatus.NOT_REQUIRED,
                        null,
                        "ATLASSIAN");

        Map<String, String> headers = invokeRemoteHeaders(manager, config);

        assertEquals(Map.of("Authorization", "Bearer live-access-token"), headers);
    }

    @Test
    void remoteHeadersFallsBackToTheStaticEnvWhenOauthProviderIsNotSet()
            throws ReflectiveOperationException {
        DynamicMcpConnectionManager manager =
                newManager(mock(McpRegistryService.class), mock(TenantSchemaIterator.class));
        McpDynamicConfig config =
                new McpDynamicConfig(
                        UUID.randomUUID(),
                        "some-other-remote-server",
                        McpServerType.SSE,
                        null,
                        null,
                        Map.of("Authorization", "Bearer a-real-pasted-token"),
                        "https://example.com/mcp",
                        false,
                        McpReviewStatus.NOT_REQUIRED,
                        null,
                        null);

        Map<String, String> headers = invokeRemoteHeaders(manager, config);

        assertEquals(Map.of("Authorization", "Bearer a-real-pasted-token"), headers);
    }

    @Test
    void remoteRequestHeadersCaptureTheConnectThreadsTenantForLaterPerRequestUse() {
        DynamicMcpConnectionManager manager =
                newManager(mock(McpRegistryService.class), mock(TenantSchemaIterator.class));
        McpDynamicConfig config =
                new McpDynamicConfig(
                        UUID.randomUUID(),
                        "public-remote",
                        McpServerType.STREAMABLE_HTTP,
                        null,
                        List.of(),
                        Map.of("X-Api-Key", "k"),
                        "https://mcp.example.com/mcp");
        TenantContext.set(TenantContext.of("tenant_a", "user-1", Set.of()));
        McpRemoteHeaders.PerRequestHeaders headers;
        try {
            headers = manager.remoteRequestHeaders(config);
        } finally {
            TenantContext.clear();
        }

        java.net.http.HttpRequest.Builder rb =
                java.net.http.HttpRequest.newBuilder(
                        java.net.URI.create("https://mcp.example.com/mcp"));
        headers.apply(rb);

        assertEquals(Optional.of("k"), rb.build().headers().firstValue("X-Api-Key"));
    }
}
