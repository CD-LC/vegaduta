package com.agenticai.core.agents.trust;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * {@link DelegationTokenRecord} is the per-{@code jti} introspection row behind every minted
 * delegation JWT. It is a plain entity: these pin the accessor contract and the defaults the mint
 * path relies on (ACTIVE status, stamped timestamps, no wallet binding unless a {@code pay:*} scope
 * asked for one).
 */
class DelegationTokenRecordTest {

    @Test
    void aFreshRecordIsActiveWithStampedTimes() {
        DelegationTokenRecord r = new DelegationTokenRecord();

        assertEquals(DelegationStatus.ACTIVE, r.getStatus());
        assertNotNull(r.getIssuedAt());
        assertNotNull(r.getCreatedAt());
        assertNull(r.getWalletId());
    }

    @Test
    void settersRoundTripEveryField() {
        UUID grant = UUID.randomUUID();
        UUID agent = UUID.randomUUID();
        UUID wallet = UUID.randomUUID();
        Instant issued = Instant.parse("2026-01-01T00:00:00Z");
        Instant expires = Instant.parse("2026-01-01T00:05:00Z");

        DelegationTokenRecord r = new DelegationTokenRecord();
        r.setJti("jti-123");
        r.setGrantId(grant);
        r.setAgentId(agent);
        r.setAudience("https://api.example.com");
        r.setIssuedAt(issued);
        r.setExpiresAt(expires);
        r.setStatus(DelegationStatus.REVOKED);
        r.setWalletId(wallet);

        assertEquals("jti-123", r.getJti());
        assertEquals(grant, r.getGrantId());
        assertEquals(agent, r.getAgentId());
        assertEquals("https://api.example.com", r.getAudience());
        assertEquals(issued, r.getIssuedAt());
        assertEquals(expires, r.getExpiresAt());
        assertEquals(DelegationStatus.REVOKED, r.getStatus());
        assertEquals(wallet, r.getWalletId());
    }
}
