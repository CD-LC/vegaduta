package com.agenticai.core.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.mcp.dto.McpDynamicConfig;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** {@link McpRegistryService#dynamicConfigFor}: the decrypt boundary for a live connection. */
class McpRegistryDynamicConfigTest extends McpRegistryServiceFixture {

    // --- dynamicConfigFor(): decrypt boundary -----------------------------------

    @Test
    void dynamicConfigForDecryptsAndParsesArgsAndEnvForALiveConnectionAttempt() {
        McpServerRegistration entity = new McpServerRegistration("brave-search", McpServerType.STDIO, "d", true);
        entity.configureDynamic("npx", "[\"-y\",\"@modelcontextprotocol/server-brave-search\"]", "encrypted-blob");
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.of(entity));
        when(encryptionService.decrypt("encrypted-blob")).thenReturn("{\"BRAVE_API_KEY\":\"real-key\"}");

        Optional<McpDynamicConfig> config = service.dynamicConfigFor(id);

        assertTrue(config.isPresent());
        assertEquals(List.of("-y", "@modelcontextprotocol/server-brave-search"), config.get().args());
        assertEquals(Map.of("BRAVE_API_KEY", "real-key"), config.get().env());
    }

    @Test
    void dynamicConfigForIsEmptyForAStaticRegistration() {
        McpServerRegistration entity = new McpServerRegistration("filesystem", McpServerType.STDIO, "d", true);
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.of(entity));

        assertTrue(service.dynamicConfigFor(id).isEmpty());
        verify(encryptionService, never()).decrypt(anyString());
    }

    @Test
    void dynamicConfigForIsEmptyWhenTheRegistrationDoesNotExist() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.empty());

        assertTrue(service.dynamicConfigFor(id).isEmpty());
    }

    @Test
    void dynamicConfigForFallsBackToAnEmptyEnvMapWhenDecryptionFailsRatherThanThrowing() {
        // A decrypt/parse failure here must degrade safely (empty map), not
        // propagate and break DynamicMcpConnectionManager's connect attempt
        // in some harder-to-diagnose way - see decryptEnvDetailed's own
        // catch-and-log. It must ALSO be flagged distinctly via
        // credentialsCorrupted (2026-08-05 fix) - previously this exact
        // scenario was indistinguishable from "tenant configured nothing".
        McpServerRegistration entity = new McpServerRegistration("brave-search", McpServerType.STDIO, "d", true);
        entity.configureDynamic("npx", "[]", "corrupted-blob");
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.of(entity));
        when(encryptionService.decrypt("corrupted-blob")).thenThrow(new RuntimeException("bad ciphertext"));

        Optional<McpDynamicConfig> config = service.dynamicConfigFor(id);

        assertTrue(config.isPresent());
        assertTrue(config.get().env().isEmpty());
        assertTrue(config.get().credentialsCorrupted(), "a real decrypt failure must be flagged, not silent");
    }

    @Test
    void dynamicConfigForDoesNotFlagCredentialsCorruptedWhenTheTenantGenuinelyConfiguredNoEnv() {
        // The other half of the same fix: genuinely-empty env (nothing ever
        // stored) must NOT be flagged as corrupted - only a real ciphertext
        // that failed to decrypt/parse counts.
        McpServerRegistration entity = new McpServerRegistration("filesystem", McpServerType.STDIO, "d", true);
        entity.configureDynamic("npx", "[]", null);
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.of(entity));

        Optional<McpDynamicConfig> config = service.dynamicConfigFor(id);

        assertTrue(config.isPresent());
        assertTrue(config.get().env().isEmpty());
        assertFalse(config.get().credentialsCorrupted());
        verify(encryptionService, never()).decrypt(anyString());
    }

    @Test
    void dynamicConfigForCarriesTheStoredReviewStatusAndRejectionReason() {
        McpServerRegistration entity = new McpServerRegistration("my-custom-server", McpServerType.STDIO, "d", true);
        entity.configureDynamic("npx", "[]", null);
        entity.rejectReview("unverified publisher");
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.of(entity));

        Optional<McpDynamicConfig> config = service.dynamicConfigFor(id);

        assertTrue(config.isPresent());
        assertEquals(McpReviewStatus.REJECTED, config.get().reviewStatus());
        assertEquals("unverified publisher", config.get().reviewRejectionReason());
    }

    @Test
    void dynamicConfigForFallsBackToAnEmptyArgsListWhenStoredArgsJsonIsUnparseable() {
        McpServerRegistration entity = new McpServerRegistration("brave-search", McpServerType.STDIO, "d", true);
        entity.configureDynamic("npx", "not valid json", null);
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.of(entity));

        Optional<McpDynamicConfig> config = service.dynamicConfigFor(id);

        assertTrue(config.isPresent());
        assertTrue(config.get().args().isEmpty());
    }
}
