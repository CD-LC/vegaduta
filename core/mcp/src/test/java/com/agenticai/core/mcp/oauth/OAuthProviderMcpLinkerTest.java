package com.agenticai.core.mcp.oauth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.mcp.McpConnectDispatcher;
import com.agenticai.core.mcp.McpConnectionStatus;
import com.agenticai.core.mcp.McpDynamicConnector;
import com.agenticai.core.mcp.McpRegistryService;
import com.agenticai.core.mcp.McpReviewStatus;
import com.agenticai.core.mcp.McpServerRegistrationNotFoundException;
import com.agenticai.core.mcp.McpServerType;
import com.agenticai.core.mcp.McpUsageStatus;
import com.agenticai.core.mcp.dto.McpServerRegistrationRequest;
import com.agenticai.core.mcp.dto.McpServerRegistrationResponse;
import com.agenticai.core.mcp.oauth.OAuthProviderRegistry.Provider;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class OAuthProviderMcpLinkerTest {

    private McpRegistryService registry;
    private McpDynamicConnector connector;
    private OAuthProviderService providerService;
    private OAuthProviderMcpLinker linker;
    private OAuthProviderRegistry providers;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        registry = mock(McpRegistryService.class);
        connector = mock(McpDynamicConnector.class);
        providerService = mock(OAuthProviderService.class);
        ObjectProvider<McpDynamicConnector> connectorProvider = mock(ObjectProvider.class);
        when(connectorProvider.getIfAvailable()).thenReturn(connector);
        linker = new OAuthProviderMcpLinker(registry, connectorProvider,
            new McpConnectDispatcher(new SyncTaskExecutor()), providerService);
        providers = OAuthProviderTestSupport.registry(OAuthProviderTestSupport.props("github", "linear", "zoom"));
    }

    private Provider provider(String id) {
        return providers.find(id).orElseThrow();
    }

    private static McpServerRegistrationResponse registration(UUID id, String name, String url) {
        return new McpServerRegistrationResponse(
            id, name, McpServerType.STREAMABLE_HTTP, "desc", true, true,
            null, null, url, McpConnectionStatus.NOT_CONFIGURED, null, null, null,
            McpUsageStatus.UNTESTED, null, null, null, null, McpReviewStatus.NOT_REQUIRED, null,
            false, false, false, List.of());
    }

    private OAuthProviderService.ProviderView view(String id, boolean connected) {
        return new OAuthProviderService.ProviderView(id, id, id, true, null, connected, List.of(), true, true, null, null);
    }

    @Test
    void connectingRegistersTheProvidersMcpServerStampsTheMarkerAndConnectsIt() {
        UUID id = UUID.randomUUID();
        when(registry.register(any(McpServerRegistrationRequest.class)))
            .thenReturn(registration(id, "github-oauth", "https://api.githubcopilot.com/mcp/"));

        UUID registered = linker.registerServerFor(provider("github"));

        assertEquals(id, registered);
        ArgumentCaptor<McpServerRegistrationRequest> req = ArgumentCaptor.forClass(McpServerRegistrationRequest.class);
        verify(registry).register(req.capture());
        assertEquals("github-oauth", req.getValue().name());
        assertEquals(McpServerType.STREAMABLE_HTTP, req.getValue().type());
        assertEquals("https://api.githubcopilot.com/mcp/", req.getValue().url());
        assertEquals(Map.of(), req.getValue().env(), "no static credential: the live token comes from the connection");
        verify(registry).setOAuthProvider(id, "oauth:github");
        verify(connector).connect(id);
    }

    @Test
    void aProviderWithoutAnMcpServerRegistersNothing() {
        assertNull(linker.registerServerFor(provider("zoom")));

        verify(registry, never()).register(any());
        verify(connector, never()).connect(any());
    }

    @Test
    void attachMarksAnExistingRegistrationOnTheProvidersOwnHostAndReconnectsIt() {
        UUID id = UUID.randomUUID();
        when(registry.listAll()).thenReturn(List.of(registration(id, "my-linear", "https://mcp.linear.app/sse")));
        when(providerService.listForCurrentTenant()).thenReturn(List.of(view("linear", true)));

        linker.attach(provider("linear"), id);

        verify(registry).setOAuthProvider(id, "oauth:linear");
        verify(connector).connect(id);
    }

    @Test
    void attachRefusesAServerOnAnyOtherHostSoTheTokenCannotBeAimedElsewhere() {
        UUID id = UUID.randomUUID();
        when(registry.listAll()).thenReturn(List.of(registration(id, "evil", "https://evil.example.com/mcp")));
        when(providerService.listForCurrentTenant()).thenReturn(List.of(view("linear", true)));

        assertThrows(IllegalArgumentException.class, () -> linker.attach(provider("linear"), id));

        verify(registry, never()).setOAuthProvider(any(), any());
    }

    @Test
    void attachNeedsAKnownRegistrationAndAnActiveConnection() {
        UUID id = UUID.randomUUID();
        when(registry.listAll()).thenReturn(List.of());
        assertThrows(McpServerRegistrationNotFoundException.class, () -> linker.attach(provider("linear"), id));

        when(registry.listAll()).thenReturn(List.of(registration(id, "my-linear", "https://mcp.linear.app/mcp")));
        when(providerService.listForCurrentTenant()).thenReturn(List.of(view("linear", false)));
        ResponseStatusException e = assertThrows(ResponseStatusException.class, () -> linker.attach(provider("linear"), id));
        assertEquals(HttpStatus.CONFLICT, e.getStatusCode());
        verify(registry, never()).setOAuthProvider(any(), any());
    }

    @Test
    void disconnectingStopsEveryLiveConnectionAndDeletesOnlyTheServerThePlatformCreated() {
        UUID created = UUID.randomUUID();
        UUID attached = UUID.randomUUID();
        when(registry.listByOAuthProvider("oauth:linear")).thenReturn(List.of(
            registration(created, "linear-oauth", "https://mcp.linear.app/mcp"),
            registration(attached, "my-linear", "https://mcp.linear.app/sse")));

        int touched = linker.dropLiveConnections(provider("linear"));

        assertEquals(2, touched);
        verify(connector).disconnect(created);
        verify(connector).disconnect(attached);
        verify(registry).delete(created);
        verify(registry, never()).delete(attached);
    }

    @Test
    void aFailureCleaningUpOneRegistrationDoesNotStopTheOthers() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        when(registry.listByOAuthProvider("oauth:linear")).thenReturn(List.of(
            registration(a, "linear-oauth", "https://mcp.linear.app/mcp"),
            registration(b, "linear-oauth", "https://mcp.linear.app/mcp")));
        org.mockito.Mockito.doThrow(new IllegalStateException("boom")).when(connector).disconnect(a);

        linker.dropLiveConnections(provider("linear"));

        verify(connector).disconnect(b);
        verify(registry).delete(b);
    }
}
