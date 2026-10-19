package com.agenticai.core.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.mcp.dto.McpServerRegistrationRequest;
import com.agenticai.core.mcp.dto.McpServerRegistrationResponse;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * {@link McpRegistryService} owns the platform's entire tool-registry
 * surface - "the module that owns the data" for every static and dynamic
 * (#424) MCP connection an agent can reach. Had zero test coverage before
 * this suite despite mixing real business logic (upsert-by-name, static vs.
 * dynamic branching, connection-status transitions) with a security-relevant
 * boundary: dynamic connections' env vars (API keys, tokens) are encrypted
 * at rest and this is the one class that decides when to encrypt/decrypt
 * them and when a decrypt/parse failure degrades safely instead of leaking
 * a stack trace or crashing a caller.
 *
 * <p>{@link McpServerRegistration} is constructed for real (not mocked) in
 * most tests - unlike {@code ApprovalRequest} in {@code
 * HumanApprovalGateServiceTest}, its public constructor and mutators are
 * sufficient to set up every scenario below without needing JPA-populated
 * fields like {@code id}.
 *
 * <p>Other behaviour areas live in the sibling classes listed on {@link
 * McpRegistryServiceFixture}.
 */
class McpRegistryServiceTest extends McpRegistryServiceFixture {

    // --- register(): new vs existing (upsert by name) ------------------------

    @Test
    void registerCreatesANewStaticEntryWhenNoCommandIsGiven() {
        when(repository.findByName("filesystem")).thenReturn(Optional.empty());
        McpServerRegistrationRequest request =
            new McpServerRegistrationRequest("filesystem", McpServerType.STDIO, "desc", true);

        McpServerRegistrationResponse response = service.register(request);

        assertEquals("filesystem", response.name());
        assertFalse(response.dynamic());
        assertTrue(response.enabled());
    }

    @Test
    void registerUpdatesTheExistingRowByNameRatherThanInsertingADuplicate() {
        McpServerRegistration existing = new McpServerRegistration("github", McpServerType.STDIO, "old desc", false);
        when(repository.findByName("github")).thenReturn(Optional.of(existing));
        McpServerRegistrationRequest request = new McpServerRegistrationRequest("github", McpServerType.STDIO, "old desc", true);

        McpServerRegistrationResponse response = service.register(request);

        assertTrue(response.enabled());
        verify(repository, times(1)).save(existing);
    }

    // --- register(): dynamic connection config --------------------------------

    @Test
    void registerConfiguresDynamicConnectionWhenCommandIsPresent() {
        when(repository.findByName("brave-search")).thenReturn(Optional.empty());
        when(encryptionService.encrypt(anyString())).thenReturn("encrypted-env-blob");
        McpServerRegistrationRequest request = new McpServerRegistrationRequest(
            "brave-search", McpServerType.STDIO, "desc", true,
            "npx", List.of("-y", "@modelcontextprotocol/server-brave-search"), Map.of("BRAVE_API_KEY", "secret-key"));

        McpServerRegistrationResponse response = service.register(request);

        assertTrue(response.dynamic());
        assertEquals("npx", response.command());
        verify(encryptionService).encrypt(org.mockito.ArgumentMatchers.contains("secret-key"));
    }

    @Test
    void registerNeverEncryptsAnythingForAStaticRegistration() {
        when(repository.findByName("filesystem")).thenReturn(Optional.empty());
        McpServerRegistrationRequest request =
            new McpServerRegistrationRequest("filesystem", McpServerType.STDIO, "desc", true);

        service.register(request);

        verify(encryptionService, never()).encrypt(anyString());
    }

    @Test
    void registerWithNoEnvMapStillConfiguresDynamicallyWithoutEncryptingAnything() {
        // command set, env null/empty - args still get JSON-serialized (an
        // empty list, not null), but encryptEnv must short-circuit rather
        // than encrypting an empty/garbage blob.
        when(repository.findByName("puppeteer")).thenReturn(Optional.empty());
        McpServerRegistrationRequest request = new McpServerRegistrationRequest(
            "puppeteer", McpServerType.STDIO, "desc", true, "npx", List.of("-y", "@modelcontextprotocol/server-puppeteer"), null);

        McpServerRegistrationResponse response = service.register(request);

        assertTrue(response.dynamic());
        verify(encryptionService, never()).encrypt(anyString());
    }

    @Test
    void registerWithNoEnvMapOnAnExistingDynamicEntryPreservesTheStoredCredentials() {
        // MCP self-heal "Apply suggested fix" (web/app/tools/servers/page.tsx)
        // re-POSTs command/args with env omitted - env==null here must mean
        // "field not sent", not "clear the stored credentials". Only an
        // explicit (possibly empty) env map should ever overwrite encryptedEnv.
        McpServerRegistration existing = new McpServerRegistration("brave-search", McpServerType.STDIO, "desc", true);
        existing.configureDynamic("npx", "[]", "already-encrypted-blob");
        when(repository.findByName("brave-search")).thenReturn(Optional.of(existing));
        McpServerRegistrationRequest request = new McpServerRegistrationRequest(
            "brave-search", McpServerType.STDIO, "desc", true,
            "npx", List.of("-y", "@modelcontextprotocol/server-brave-search", "--flag"), null);

        service.register(request);

        assertEquals("already-encrypted-blob", existing.getEncryptedEnv());
        verify(encryptionService, never()).encrypt(anyString());
    }

    // --- register(): the guard runs BEFORE anything is persisted --------------

    @Test
    void registerRejectsARuntimeControlEnvVarBeforePersistingAnything() {
        // Security audit 2026-07-29 (§2 item 2): env was inspected by nothing.
        // A registration whose command/args are entirely innocent still reaches
        // RCE through NODE_OPTIONS, so it must never become a stored row.
        when(repository.findByName("brave-search")).thenReturn(Optional.empty());
        McpServerRegistrationRequest request = new McpServerRegistrationRequest(
            "brave-search", McpServerType.STDIO, "desc", true,
            "npx", List.of("-y", "@modelcontextprotocol/server-brave-search"),
            Map.of("NODE_OPTIONS", "--import data:text/javascript,import('node:child_process').execSync('id')"));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> service.register(request));

        assertTrue(ex.getMessage().contains("NODE_OPTIONS"), ex.getMessage());
        verify(repository, never()).save(any());
        verify(encryptionService, never()).encrypt(anyString());
    }

    @Test
    void registerRejectsAModuleLoadingArgFlag() {
        when(repository.findByName("brave-search")).thenReturn(Optional.empty());
        McpServerRegistrationRequest request = new McpServerRegistrationRequest(
            "brave-search", McpServerType.STDIO, "desc", true,
            "node", List.of("--require", "/tmp/evil.js", "server.js"), Map.of());

        assertThrows(IllegalArgumentException.class, () -> service.register(request));
        verify(repository, never()).save(any());
    }

    @Test
    void registerLeavesRemoteRegistrationEnvAloneBecauseItBecomesHttpHeaders() {
        // The env denylist is about a PROCESS environment. On a remote
        // registration those entries become request headers instead (see
        // DynamicMcpConnectionManager#remoteHeaders), so applying the STDIO
        // env rules there would reject legitimate header names for no gain.
        when(repository.findByName("remote-server")).thenReturn(Optional.empty());
        when(encryptionService.encrypt(anyString())).thenReturn("encrypted-env-blob");
        McpServerRegistrationRequest request = new McpServerRegistrationRequest(
            "remote-server", McpServerType.STREAMABLE_HTTP, "desc", true,
            null, null, Map.of("Authorization", "Bearer abc"), "https://mcp.example.com/mcp");

        McpServerRegistrationResponse response = service.register(request);

        assertEquals("remote-server", response.name());
    }

    // --- setEnabled() ----------------------------------------------------------

    @Test
    void setEnabledThrowsWhenTheRegistrationDoesNotExist() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.empty());

        assertThrows(McpServerRegistrationNotFoundException.class, () -> service.setEnabled(id, true));
    }

    @Test
    void disablingADynamicRegistrationMarksItDisabledSoNoStaleConnectedStatusLingers() {
        McpServerRegistration entity = new McpServerRegistration("github", McpServerType.STDIO, "desc", true);
        entity.configureDynamic("npx", "[]", "encrypted");
        entity.markConnected();
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.of(entity));

        service.setEnabled(id, false);

        assertEquals(McpConnectionStatus.DISABLED, entity.getConnectionStatus());
        assertFalse(entity.isEnabled());
    }

    @Test
    void disablingAStaticRegistrationDoesNotTouchConnectionStatus() {
        McpServerRegistration entity = new McpServerRegistration("filesystem", McpServerType.STDIO, "desc", true);
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.of(entity));

        service.setEnabled(id, false);

        assertEquals(McpConnectionStatus.NOT_CONFIGURED, entity.getConnectionStatus());
    }

    @Test
    void enablingADisabledRegistrationDoesNotMarkDisabledAgain() {
        McpServerRegistration entity = new McpServerRegistration("github", McpServerType.STDIO, "desc", false);
        entity.configureDynamic("npx", "[]", "encrypted");
        entity.markDisabled();
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.of(entity));

        service.setEnabled(id, true);

        assertTrue(entity.isEnabled());
    }

    // --- anyServerEnabled() / enabledDynamicRegistrations() -------------------

    @Test
    void anyServerEnabledIsFalseWhenNothingIsEnabled() {
        when(repository.findByEnabledTrue()).thenReturn(List.of());

        assertFalse(service.anyServerEnabled());
    }

    @Test
    void enabledDynamicRegistrationsFiltersOutEnabledStaticRows() {
        McpServerRegistration staticEntry = new McpServerRegistration("filesystem", McpServerType.STDIO, "d", true);
        McpServerRegistration dynamicEntry = new McpServerRegistration("github", McpServerType.STDIO, "d", true);
        dynamicEntry.configureDynamic("npx", "[]", "enc");
        when(repository.findByEnabledTrue()).thenReturn(List.of(staticEntry, dynamicEntry));

        List<McpServerRegistration> dynamic = service.enabledDynamicRegistrations();

        assertEquals(1, dynamic.size());
        assertEquals("github", dynamic.get(0).getName());
    }

    // --- register(): duplicate-name warning + wasUpdate signal ------------------

    @Test
    void registerFlagsWasUpdateFalseAndNoDuplicateWarningForABrandNewName() {
        when(repository.findByName("filesystem")).thenReturn(Optional.empty());
        McpServerRegistrationRequest request =
            new McpServerRegistrationRequest("filesystem", McpServerType.STDIO, "desc", true);

        McpServerRegistrationResponse response = service.register(request);

        assertFalse(response.wasUpdate());
        assertTrue(response.warnings().isEmpty());
    }

    @Test
    void registerFlagsWasUpdateTrueAndAddsADuplicateNameWarningForAnExistingName() {
        McpServerRegistration existing = new McpServerRegistration("filesystem", McpServerType.STDIO, "old", false);
        when(repository.findByName("filesystem")).thenReturn(Optional.of(existing));
        McpServerRegistrationRequest request =
            new McpServerRegistrationRequest("filesystem", McpServerType.STDIO, "desc", true);

        McpServerRegistrationResponse response = service.register(request);

        assertTrue(response.wasUpdate());
        assertTrue(response.warnings().stream().anyMatch(w -> w.contains("already existed")));
    }

    // --- markConnecting/markConnected/markError -----------------------------

    @Test
    void markConnectedTransitionsStatusAndStampsConnectedAt() {
        McpServerRegistration entity = new McpServerRegistration("github", McpServerType.STDIO, "d", true);
        entity.configureDynamic("npx", "[]", "enc");
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.of(entity));

        service.markConnected(id);

        assertEquals(McpConnectionStatus.CONNECTED, entity.getConnectionStatus());
        assertTrue(entity.getConnectedAt() != null);
        verify(repository).save(entity);
    }

    @Test
    void markErrorRecordsTheErrorMessageAndClearsConnectedAt() {
        McpServerRegistration entity = new McpServerRegistration("github", McpServerType.STDIO, "d", true);
        entity.configureDynamic("npx", "[]", "enc");
        entity.markConnected();
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.of(entity));

        service.markError(id, "npx not found on PATH");

        assertEquals(McpConnectionStatus.ERROR, entity.getConnectionStatus());
        assertEquals("npx not found on PATH", entity.getConnectionError());
        assertEquals(null, entity.getConnectedAt());
    }

    @Test
    void markConnectingIsANoOpWhenTheRegistrationNoLongerExists() {
        // Documented ifPresent-based shape - a race with a concurrent delete
        // must not throw.
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.empty());

        service.markConnecting(id);

        verify(repository, never()).save(any());
    }
}
