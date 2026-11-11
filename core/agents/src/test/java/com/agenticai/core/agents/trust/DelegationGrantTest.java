package com.agenticai.core.agents.trust;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * {@link DelegationGrant} is the immutable-after-creation RFC 8693 grant record. These pin the
 * spend-cap accounting ({@code applySpend}/{@code releaseSpend}, the only mutations allowed) and
 * the {@code markRevoked} transition, all called under the grant's row lock by {@code
 * DelegationTokenService}.
 */
class DelegationGrantTest {

    private static DelegationGrant grant() {
        DelegationGrant g = new DelegationGrant();
        g.setId(UUID.randomUUID());
        g.setAgentId(UUID.randomUUID());
        g.setGrantedBy("delegator-sub");
        g.setAudience("https://api.example.com");
        g.setScopesJson("[\"read:calendar\"]");
        g.setMaxTokenTtlSeconds(300);
        g.setExpiresAt(Instant.now().plusSeconds(3600));
        return g;
    }

    @Test
    void aNewGrantIsActiveWithNothingSpent() {
        DelegationGrant g = grant();

        assertEquals(DelegationStatus.ACTIVE, g.getStatus());
        assertEquals(0, g.getSpentMinor());
        assertNull(g.getSpendCapMinor());
        assertNull(g.getWalletId());
        assertNull(g.getRevokedAt());
        assertNull(g.getRevokedBy());
        assertNull(g.getRevokeReason());
        assertNotNull(g.getCreatedAt());
        assertNotNull(g.getUpdatedAt());
    }

    @Test
    void applySpendAccumulatesAndStampsUpdatedAt() {
        DelegationGrant g = grant();
        g.setSpendCapMinor(1_000L);
        Instant at = Instant.parse("2026-01-01T00:00:00Z");

        g.applySpend(250, at);
        g.applySpend(300, at);

        assertEquals(550, g.getSpentMinor());
        assertEquals(at, g.getUpdatedAt());
        assertEquals(1_000L, g.getSpendCapMinor());
    }

    @Test
    void releaseSpendFloorsAtZero() {
        DelegationGrant g = grant();
        Instant at = Instant.parse("2026-01-01T00:00:00Z");

        g.applySpend(100, at);
        g.releaseSpend(60, at);
        assertEquals(40, g.getSpentMinor());

        // A compensating release larger than the recorded spend clamps to 0, never goes negative.
        g.releaseSpend(999, at);
        assertEquals(0, g.getSpentMinor());
    }

    @Test
    void applySpendOverflowThrowsRatherThanWrapping() {
        DelegationGrant g = grant();
        g.applySpend(1, Instant.now());
        assertThrows(ArithmeticException.class, () -> g.applySpend(Long.MAX_VALUE, Instant.now()));
    }

    @Test
    void markRevokedRecordsWhoWhenAndWhy() {
        DelegationGrant g = grant();
        Instant at = Instant.parse("2026-02-01T12:00:00Z");

        g.markRevoked("admin-sub", "user asked for it", at);

        assertEquals(DelegationStatus.REVOKED, g.getStatus());
        assertEquals(at, g.getRevokedAt());
        assertEquals("admin-sub", g.getRevokedBy());
        assertEquals("user asked for it", g.getRevokeReason());
        assertEquals(at, g.getUpdatedAt());
        assertTrue(g.getSpentMinor() == 0, "revoking does not erase the spend history");
    }

    @Test
    void theWalletBindingIsPlainData() {
        DelegationGrant g = grant();
        UUID wallet = UUID.randomUUID();
        g.setWalletId(wallet);
        assertEquals(wallet, g.getWalletId());
        assertEquals(300, g.getMaxTokenTtlSeconds());
        assertEquals("delegator-sub", g.getGrantedBy());
        assertEquals("https://api.example.com", g.getAudience());
        assertEquals("[\"read:calendar\"]", g.getScopesJson());
    }
}
