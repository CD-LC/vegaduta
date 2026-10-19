package com.agenticai.core.mcp.oauth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.mcp.oauth.McpOAuthSharedStore.CachedToken;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Default ({@code app.mcp.oauth.store=memory}) behaviour - today's single-replica semantics. */
class InMemoryMcpOAuthSharedStoreTest {

    private static final Instant T0 = Instant.parse("2026-10-05T10:00:00Z");

    private final OAuthProviderTestSupport.MutableClock clock =
            new OAuthProviderTestSupport.MutableClock(T0);
    private final InMemoryMcpOAuthSharedStore store = new InMemoryMcpOAuthSharedStore(clock);

    @Test
    void aNonceClaimsOncePerKind() {
        Instant expiry = T0.plusSeconds(600);
        assertTrue(store.claimNonce(McpOAuthSharedStore.STATE_KIND_REMOTE, "n1", expiry));
        assertFalse(store.claimNonce(McpOAuthSharedStore.STATE_KIND_REMOTE, "n1", expiry));
        // The provider flow's namespace is separate even for the same nonce text.
        assertTrue(store.claimNonce(McpOAuthSharedStore.STATE_KIND_PROVIDER, "n1", expiry));
        assertTrue(store.claimNonce(McpOAuthSharedStore.STATE_KIND_REMOTE, "n2", expiry));
    }

    @Test
    void anExpiredNonceEntryIsSweptAndCanBeClaimedAgain() {
        assertTrue(store.claimNonce(McpOAuthSharedStore.STATE_KIND_REMOTE, "n1", T0.plusSeconds(30)));
        clock.advance(Duration.ofSeconds(31));
        // Expired states are refused by decode() before consume() runs; the entry itself lapses.
        assertTrue(store.claimNonce(McpOAuthSharedStore.STATE_KIND_REMOTE, "n1", T0.plusSeconds(600)));
    }

    @Test
    void tokensRoundTripPerTenantAndKey() {
        CachedToken token = new CachedToken("at-1", T0.plusSeconds(300));
        assertEquals(Optional.empty(), store.token("t1", "https://mcp.example.com/mcp"));
        store.putToken("t1", "https://mcp.example.com/mcp", token);
        assertEquals(Optional.of(token), store.token("t1", "https://mcp.example.com/mcp"));
        // Same key under another tenant is a different entry.
        assertEquals(Optional.empty(), store.token("t2", "https://mcp.example.com/mcp"));
        store.removeToken("t1", "https://mcp.example.com/mcp");
        assertEquals(Optional.empty(), store.token("t1", "https://mcp.example.com/mcp"));
    }

    @Test
    void theRefreshLockReturnsTheActionResultAndIsReentrantPerThread() {
        String out = store.withRefreshLock("t1", "github",
            () -> store.withRefreshLock("t1", "github", () -> "inner"));
        assertEquals("inner", out);
    }
}
