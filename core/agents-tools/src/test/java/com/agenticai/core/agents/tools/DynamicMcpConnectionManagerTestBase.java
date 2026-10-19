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

/**
 * {@link DynamicMcpConnectionManager}'s {@code connect}/{@code disconnect} flow spawns a real
 * subprocess through a reflectively-resolved MCP SDK constructor (see that class's own javadoc on
 * the unverified {@code StdioClientTransport} arity) - not something this sandbox can compile/run
 * against a real SDK, so it has no coverage here. This suite is scoped to the one piece of that
 * class that's pure, deterministic, and worth locking in regardless: {@link
 * DynamicMcpConnectionManager#redactSecrets}, the credential-isolation fix that keeps a tenant's
 * own configured secret values out of the persisted/returned {@code connectionError} field when a
 * misbehaving MCP server's own failure text happens to echo one back.
 *
 * <p>The tests below this comment cover the parts of {@code connect}/{@code disconnect} that run
 * entirely BEFORE the reflective SDK call (config lookup, the "no dynamic config" short-circuit,
 * the empty-disconnect no-op, {@code resolveDynamicToolCallbacks}' default) - none of these touch
 * {@code ServerParameters}/{@code McpClient}/ {@code StdioClientTransport}, so they don't share the
 * SDK-shape risk called out above. {@link McpRegistryService} and {@link TenantSchemaIterator} are
 * both plain, non-final Spring {@code @Component}/{@code @Service} classes (not interfaces) -
 * mocked directly with {@code Mockito.mock(...)}, same pattern {@code AgentServiceTest} already
 * uses in this module for concrete collaborator classes.
 */
abstract class DynamicMcpConnectionManagerTestBase {

    /**
     * Production-default guards (pure classes) + default per-tenant stdio cap - what every test
     * here constructs the manager with. {@link McpStdioSelfHealer} is mocked, never real: none of
     * these tests reach the reflective SDK call that would actually produce a stdio failure for it
     * to react to (see class javadoc), so a mock that's never invoked is all that's needed to
     * satisfy the constructor.
     */
    static DynamicMcpConnectionManager newManager(
            McpRegistryService registryService, TenantSchemaIterator tenantSchemaIterator) {
        return newManager(registryService, tenantSchemaIterator, noAgentToolProvider());
    }

    /**
     * Bug-fix (2026-08-06) constructor param - see evictToolCache's own test group below. Every
     * pre-existing test in this file uses this "nothing available" default, unaffected by it.
     */
    @SuppressWarnings("unchecked")
    static ObjectProvider<AgentToolProvider> noAgentToolProvider() {
        ObjectProvider<AgentToolProvider> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        return provider;
    }

    /**
     * An empty ObjectProvider for collaborators a test doesn't care about (e.g.
     * AgentAttestationService).
     */
    @SuppressWarnings("unchecked")
    static <T> ObjectProvider<T> emptyProvider() {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        return provider;
    }

    static DynamicMcpConnectionManager newManager(
            McpRegistryService registryService,
            TenantSchemaIterator tenantSchemaIterator,
            ObjectProvider<AgentToolProvider> agentToolProvider) {
        // Never invoked by any pre-existing test in this file (none reach
        // the atlassian oauthProvider branch of remoteHeaders) - a plain
        // mock is fine here; the remoteHeaders-specific tests below use the
        // 4-arg overload to inject a stubbed one.
        return newManager(
                registryService,
                tenantSchemaIterator,
                agentToolProvider,
                mock(AtlassianOAuthService.class));
    }

    static DynamicMcpConnectionManager newManager(
            McpRegistryService registryService,
            TenantSchemaIterator tenantSchemaIterator,
            ObjectProvider<AgentToolProvider> agentToolProvider,
            AtlassianOAuthService atlassianOAuthService) {
        // A throwaway SimpleMeterRegistry - tests that assert on metrics use the overload below.
        return newManager(
                registryService,
                tenantSchemaIterator,
                agentToolProvider,
                atlassianOAuthService,
                new SimpleMeterRegistry());
    }

    static DynamicMcpConnectionManager newManager(
            McpRegistryService registryService,
            TenantSchemaIterator tenantSchemaIterator,
            ObjectProvider<AgentToolProvider> agentToolProvider,
            AtlassianOAuthService atlassianOAuthService,
            io.micrometer.core.instrument.MeterRegistry meterRegistry) {
        return new DynamicMcpConnectionManager(
                registryService,
                tenantSchemaIterator,
                new McpRemoteUrlGuard(""),
                new McpStdioCommandGuard(""),
                mock(McpStdioSelfHealer.class),
                // Real catalog (not a mock), same reasoning McpRegistryServiceTest's setUp already
                // documents for itself - the review-gate/missing-env-var tests below need real
                // name matches (e.g. "github" IS a catalog entry, "my-custom-server" is NOT).
                new McpCatalogService(),
                5,
                // Disabled by default (real production default too - see
                // McpStdioSidecarProperties#enabled) - every existing test in this
                // file exercises the direct-spawn path, unchanged by the sidecar
                // feature's existence.
                new McpStdioSidecarProperties(),
                agentToolProvider,
                atlassianOAuthService,
                meterRegistry,
                // No attestation bean in tests - same "absent, never wrong" contract
                // the service documents.
                emptyProvider(),
                cacheBase.toString());
    }

    /** One owner-only base per test class, same as app.mcp.package-cache-dir resolves to. */
    @org.junit.jupiter.api.io.TempDir static java.nio.file.Path cacheBase;

    /**
     * Resolved like the manager does ({@code toRealPath}: macOS temp dirs sit behind a symlink).
     */
    static String cacheDir(String root, UUID id) throws java.io.IOException {
        return cacheBase.toRealPath().resolve(root).resolve(id.toString()).toString();
    }

    @SuppressWarnings("unchecked")
    static ObjectProvider<AgentToolProvider> mockProviderOf(
            AgentToolProvider agentToolProvider) {
        ObjectProvider<AgentToolProvider> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(agentToolProvider);
        return provider;
    }

    // --- 2026-08-19 MCP-blackout follow-up sweep: health-check dead-client teardown ---------

    /**
     * {@link DynamicMcpConnectionManager#buildRemoteTransport} builds the MCP SDK's OWN {@code
     * HttpClient} directly (not through the reflective, arity-uncertain STDIO constructor lookup
     * this class's own javadoc warns about - see that javadoc's own "every other SDK call here is
     * used the same way... called directly" note), so unlike the STDIO path this one IS safe to
     * call and inspect in a plain unit test. Reflection here is only to reach the transport's
     * private {@code httpClient} field - there is no public getter for a built transport's redirect
     * policy - not because the SDK shape itself is uncertain.
     */
    static HttpClient extractHttpClient(McpClientTransport transport)
            throws ReflectiveOperationException {
        Field field = transport.getClass().getDeclaredField("httpClient");
        field.setAccessible(true);
        return (HttpClient) field.get(transport);
    }

    static McpDynamicConfig stdioConfig(
            UUID id, String name, McpReviewStatus reviewStatus, String rejectionReason) {
        return new McpDynamicConfig(
                id,
                name,
                McpServerType.STDIO,
                "npx",
                List.of("-y", "some-package"),
                Map.of(),
                null,
                false,
                reviewStatus,
                rejectionReason);
    }

    static DynamicMcpConnectionManager newManagerWithSidecar(
            McpRegistryService registryService,
            TenantSchemaIterator tenantSchemaIterator,
            McpStdioSidecarProperties props) {
        return new DynamicMcpConnectionManager(
                registryService,
                tenantSchemaIterator,
                new McpRemoteUrlGuard(""),
                new McpStdioCommandGuard(""),
                mock(McpStdioSelfHealer.class),
                new McpCatalogService(),
                5,
                props,
                noAgentToolProvider(),
                mock(AtlassianOAuthService.class),
                new SimpleMeterRegistry(),
                emptyProvider(),
                cacheBase.toString());
    }

    static ServerParameters invokeStdioParamsFor(
            DynamicMcpConnectionManager manager, McpDynamicConfig config)
            throws ReflectiveOperationException {
        Method m =
                DynamicMcpConnectionManager.class.getDeclaredMethod(
                        "stdioParamsFor", McpDynamicConfig.class);
        m.setAccessible(true);
        return (ServerParameters) m.invoke(manager, config);
    }

    @SuppressWarnings("unchecked")
    static Map<String, String> invokeRemoteHeaders(
            DynamicMcpConnectionManager manager, McpDynamicConfig config)
            throws ReflectiveOperationException {
        Method m =
                DynamicMcpConnectionManager.class.getDeclaredMethod(
                        "remoteHeaders", McpDynamicConfig.class);
        m.setAccessible(true);
        return (Map<String, String>) m.invoke(manager, config);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Map<UUID, Object>> liveMap(
            DynamicMcpConnectionManager manager, String field) throws ReflectiveOperationException {
        Field f = DynamicMcpConnectionManager.class.getDeclaredField(field);
        f.setAccessible(true);
        return (Map<String, Map<UUID, Object>>) f.get(manager);
    }

    static ToolCallback rawTool(String name) {
        ToolCallback cb = mock(ToolCallback.class);
        when(cb.getToolDefinition())
                .thenReturn(
                        new org.springframework.ai.tool.definition.DefaultToolDefinition(
                                name, "desc", "{\"type\":\"object\"}"));
        return cb;
    }
}
