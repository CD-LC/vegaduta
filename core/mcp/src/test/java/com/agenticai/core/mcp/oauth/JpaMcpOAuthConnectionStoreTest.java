package com.agenticai.core.mcp.oauth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.mcp.oauth.McpOAuthConnectionStore.McpOAuthConnection;
import com.agenticai.core.security.TenantContext;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class JpaMcpOAuthConnectionStoreTest {

    private static final String RESOURCE = "oauth-provider:github";

    private final McpOAuthConnectionRepository repository = mock(McpOAuthConnectionRepository.class);
    private final JpaMcpOAuthConnectionStore store = new JpaMcpOAuthConnectionStore(repository);

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private static McpOAuthConnection connection(String tenant) {
        return new McpOAuthConnection(tenant, RESOURCE, "github", "https://github.com/login/oauth/authorize",
            "https://github.com/login/oauth/access_token", "cid", null, "BODY", "https://api.example.com/cb", "repo",
            "enc-refresh", "enc-access", Instant.parse("2026-10-05T00:00:00Z"));
    }

    @Test
    void saveCreatesARowWithEveryFieldAndFindMapsItBack() {
        when(repository.findByResourceUrl(RESOURCE)).thenReturn(Optional.empty());

        store.save(connection("tenant_a"));

        ArgumentCaptor<McpOAuthConnectionEntity> saved = ArgumentCaptor.forClass(McpOAuthConnectionEntity.class);
        verify(repository).save(saved.capture());
        McpOAuthConnection roundTripped = saved.getValue().toConnection("tenant_a");
        assertEquals(connection("tenant_a"), roundTripped);

        when(repository.findByResourceUrl(RESOURCE)).thenReturn(Optional.of(saved.getValue()));
        assertEquals(connection("tenant_a"), store.find("tenant_a", RESOURCE).orElseThrow());
    }

    @Test
    void saveUpdatesTheExistingRowInsteadOfInsertingASecondOne() {
        McpOAuthConnectionEntity existing = new McpOAuthConnectionEntity(RESOURCE);
        existing.apply(connection("tenant_a"));
        when(repository.findByResourceUrl(RESOURCE)).thenReturn(Optional.of(existing));

        store.save(connection("tenant_a").withTokens(null, null, null));

        verify(repository).save(existing);
        assertEquals(false, existing.toConnection("tenant_a").isConnected());
    }

    @Test
    void deleteRemovesTheRowWhenPresentAndIsANoOpOtherwise() {
        McpOAuthConnectionEntity existing = new McpOAuthConnectionEntity(RESOURCE);
        when(repository.findByResourceUrl(RESOURCE)).thenReturn(Optional.of(existing));
        store.delete("tenant_a", RESOURCE);
        verify(repository).delete(existing);

        when(repository.findByResourceUrl("oauth-provider:slack")).thenReturn(Optional.empty());
        store.delete("tenant_a", "oauth-provider:slack");
        verify(repository, times(1)).delete(any(McpOAuthConnectionEntity.class));
    }

    @Test
    void everyCallRunsUnderTheRequestedTenantEvenWithNoAmbientContextAndRestoresAfterwards() {
        List<String> seen = new ArrayList<>();
        when(repository.findByResourceUrl(RESOURCE)).thenAnswer(inv -> {
            seen.add(TenantContext.current().map(TenantContext::getTenantId).orElse("<none>"));
            return Optional.empty();
        });

        store.find("tenant_a", RESOURCE);

        assertEquals(List.of("tenant_a"), seen);
        assertTrue(TenantContext.current().isEmpty(), "the callback thread must not keep a tenant it was never given");
    }

    @Test
    void aDifferentAmbientTenantIsSwappedForTheCallAndPutBack() {
        TenantContext ambient = TenantContext.of("tenant_b", "user-b", Set.of());
        TenantContext.set(ambient);
        List<String> seen = new ArrayList<>();
        when(repository.findByResourceUrl(RESOURCE)).thenAnswer(inv -> {
            seen.add(TenantContext.current().map(TenantContext::getTenantId).orElse("<none>"));
            return Optional.empty();
        });

        store.find("tenant_a", RESOURCE);

        assertEquals(List.of("tenant_a"), seen);
        assertSame(ambient, TenantContext.current().orElseThrow());
    }

    @Test
    void theAmbientContextIsRestoredEvenWhenTheRepositoryThrows() {
        when(repository.findByResourceUrl(RESOURCE)).thenThrow(new IllegalStateException("db down"));

        assertThrows(IllegalStateException.class, () -> store.find("tenant_a", RESOURCE));

        assertTrue(TenantContext.current().isEmpty());
    }

    @Test
    void aMissingTenantIdIsRefusedRatherThanFallingBackToTheWrongSchema() {
        assertThrows(IllegalStateException.class, () -> store.find(null, RESOURCE));
        assertThrows(IllegalStateException.class, () -> store.delete(" ", RESOURCE));
    }
}
