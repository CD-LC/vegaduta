package com.agenticai.core.mcp;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.mcp.dto.McpServerRegistrationResponse;
import com.agenticai.core.mcp.dto.McpServerToggleRequest;
import com.agenticai.core.mcp.registry.OfficialMcpRegistryClient;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.task.SyncTaskExecutor;

/**
 * {@code PATCH /api/mcp/registry/{id}} (toggle) - bug fix (2026-08-06, direct
 * report: "once tools enabled in mcp, agents still unable to access it...
 * soon after enabling, it should be available"). Root cause traced to
 * {@code AgentToolProvider}'s short-TTL MCP tool cache never being evicted on
 * enable/disable. {@code McpDynamicConnector#connect}/{@code #disconnect}
 * (core/agents' {@code DynamicMcpConnectionManager}) now evict it themselves
 * for DYNAMIC registrations - what this suite locks in is the STATIC branch,
 * which has no live connection to (re)establish and so is the one case
 * {@link McpRegistryController#toggle} must call {@code evictToolCache()}
 * directly, plus that the dynamic branch still calls connect/disconnect
 * exactly as before (unchanged behavior, not touched by this fix).
 */
class McpRegistryControllerTest {

    private static final UUID ID = UUID.randomUUID();

    private McpRegistryService service;
    private McpDynamicConnector connector;
    private McpRegistryController controller;

    private void setUp(boolean connectorAvailable) {
        service = mock(McpRegistryService.class);
        McpCatalogService catalogService = mock(McpCatalogService.class);
        OfficialMcpRegistryClient officialRegistryClient = mock(OfficialMcpRegistryClient.class);
        connector = mock(McpDynamicConnector.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<McpDynamicConnector> connectorProvider = mock(ObjectProvider.class);
        when(connectorProvider.getIfAvailable()).thenReturn(connectorAvailable ? connector : null);
        // A REAL McpConnectDispatcher wired with SyncTaskExecutor (runs
        // inline on the calling thread) - keeps this suite's verify(...)
        // calls deterministic against the dispatcher's now-off-thread
        // connect() dispatch (see McpConnectDispatcher's javadoc), while
        // still exercising the real extracted dispatch logic rather than a
        // mock of it.
        controller = new McpRegistryController(
            service, catalogService, officialRegistryClient, connectorProvider,
            new McpConnectDispatcher(new SyncTaskExecutor()));
    }

    private static McpServerRegistrationResponse response(boolean dynamic) {
        return new McpServerRegistrationResponse(
            ID, "test-server", McpServerType.STDIO, "desc", true, dynamic,
            null, null, null, McpConnectionStatus.NOT_CONFIGURED, null, null, null,
            McpUsageStatus.UNTESTED, null, null, null, null, McpReviewStatus.NOT_REQUIRED, null,
            false, false, false, List.of()
        );
    }

    @Test
    void togglingAStaticRegistrationEvictsTheToolCacheDirectlyInsteadOfConnecting() {
        setUp(true);
        when(service.setEnabled(ID, true)).thenReturn(response(false));

        controller.toggle(ID, new McpServerToggleRequest(true));

        verify(connector).evictToolCache();
        verify(connector, never()).connect(any());
        verify(connector, never()).disconnect(any());
    }

    @Test
    void togglingAStaticRegistrationOffAlsoEvictsTheToolCache() {
        setUp(true);
        when(service.setEnabled(ID, false)).thenReturn(response(false));

        controller.toggle(ID, new McpServerToggleRequest(false));

        verify(connector).evictToolCache();
        verify(connector, never()).disconnect(any());
    }

    @Test
    void togglingADynamicRegistrationOnStillCallsConnectNotTheDirectEvict() {
        setUp(true);
        when(service.setEnabled(ID, true)).thenReturn(response(true));

        controller.toggle(ID, new McpServerToggleRequest(true));

        // connect() evicts the cache itself (DynamicMcpConnectionManager) -
        // the controller must not ALSO call evictToolCache() directly for the
        // dynamic path, or a mocked connector in production code would double
        // up for no reason (harmless in the real impl, but the controller's
        // own contract is "exactly one signal per action").
        verify(connector).connect(ID);
        verify(connector, never()).disconnect(any());
        verify(connector, never()).evictToolCache();
    }

    @Test
    void togglingADynamicRegistrationOffCallsDisconnect() {
        setUp(true);
        when(service.setEnabled(ID, false)).thenReturn(response(true));

        controller.toggle(ID, new McpServerToggleRequest(false));

        verify(connector).disconnect(ID);
        verify(connector, never()).connect(any());
        verify(connector, never()).evictToolCache();
    }

    @Test
    void toggleNeverThrowsWhenNoConnectorBeanIsAvailable() {
        setUp(false);
        when(service.setEnabled(ID, true)).thenReturn(response(false));

        McpServerRegistrationResponse result = controller.toggle(ID, new McpServerToggleRequest(true));

        org.junit.jupiter.api.Assertions.assertEquals(ID, result.id());
    }

    // --- SEC-002: registry search review hint uses the exact launch spec -------

    private static com.agenticai.core.mcp.registry.RegistrySearchResult stdioResult(String name, String command, List<String> args) {
        return new com.agenticai.core.mcp.registry.RegistrySearchResult(
            name, name, "d", "1.0.0", null, McpServerType.STDIO, "npm", true, null,
            command, args, null, List.of(), "active", true);
    }

    @Test
    void sec002_registryResultReusingACatalogNameWithAnotherPackageStillRequiresReview() {
        OfficialMcpRegistryClient registry = mock(OfficialMcpRegistryClient.class);
        when(registry.search("github", null)).thenReturn(new com.agenticai.core.mcp.registry.RegistrySearchResponse(
            List.of(
                stdioResult("github", "npx", List.of("-y", "attacker-pkg")),
                stdioResult("github", "npx", List.of("-y", "@modelcontextprotocol/server-github")),
                stdioResult("github", null, List.of())),
            null, false));
        @SuppressWarnings("unchecked")
        ObjectProvider<McpDynamicConnector> connectorProvider = mock(ObjectProvider.class);
        McpRegistryController c = new McpRegistryController(
            mock(McpRegistryService.class), new McpCatalogService(), registry, connectorProvider,
            new McpConnectDispatcher(new SyncTaskExecutor()));

        List<com.agenticai.core.mcp.registry.RegistrySearchResult> results = c.search("github", null).results();

        org.junit.jupiter.api.Assertions.assertTrue(results.get(0).reviewRequired());
        org.junit.jupiter.api.Assertions.assertFalse(results.get(1).reviewRequired());
        org.junit.jupiter.api.Assertions.assertTrue(results.get(2).reviewRequired());
    }
}
