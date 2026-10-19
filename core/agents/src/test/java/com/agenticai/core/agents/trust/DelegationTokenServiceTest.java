package com.agenticai.core.agents.trust;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.a2a.A2aCardSigningService;
import com.agenticai.core.agents.a2a.A2aProperties;
import com.agenticai.core.audit.AuditLogService;
import com.agenticai.core.security.TenantContext;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Mint/introspect/verify round-trip over a REAL {@link A2aCardSigningService}
 * (ephemeral Ed25519 key - exactly what it generates when no PEM is pinned).
 * Repositories are mocked; TenantContext is set/cleared around each test.
 */
class DelegationTokenServiceTest {

    private DelegationGrantRepository grantRepository;
    private DelegationTokenRepository tokenRepository;
    private A2aCardSigningService signer;
    private DelegationTokenService service;
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-02T12:00:00Z"), ZoneOffset.UTC);

    @BeforeEach
    void setUp() {
        grantRepository = mock(DelegationGrantRepository.class);
        tokenRepository = mock(DelegationTokenRepository.class);
        signer = new A2aCardSigningService(new A2aProperties());
        ObjectProvider<A2aCardSigningService> signerProvider = mock(ObjectProvider.class);
        when(signerProvider.getIfAvailable()).thenReturn(signer);
        ObjectProvider<AuditLogService> auditProvider = mock(ObjectProvider.class);
        when(auditProvider.getIfAvailable()).thenReturn(null);
        service = new DelegationTokenService(
            grantRepository, tokenRepository, signerProvider, auditProvider,
            "https://api.example.com", 3600, clock);
        TenantContext.set(TenantContext.of("acme", "user-1", Set.of()));
        when(grantRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(tokenRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private DelegationGrant grant(List<String> scopes, UUID walletId) {
        return grant(scopes, walletId, null);
    }

    private DelegationGrant grant(List<String> scopes, UUID walletId, Long spendCapMinor) {
        UUID agentId = UUID.randomUUID();
        DelegationGrant g = service.createGrant(
            agentId, "user-1", "x402://premium-data", scopes, 0,
            Instant.parse("2026-10-03T00:00:00Z"), walletId, spendCapMinor);
        when(grantRepository.findById(g.getId())).thenReturn(Optional.of(g));
        when(grantRepository.findByIdForUpdate(g.getId())).thenReturn(Optional.of(g));
        return g;
    }

    private void registerToken(DelegationTokenService.MintedToken minted, DelegationGrant g) {
        DelegationTokenRecord rec = new DelegationTokenRecord();
        rec.setJti(minted.jti());
        rec.setGrantId(g.getId());
        rec.setAgentId(g.getAgentId());
        rec.setAudience(g.getAudience());
        rec.setExpiresAt(minted.expiresAt());
        rec.setWalletId(g.getWalletId());
        when(tokenRepository.findById(minted.jti())).thenReturn(Optional.of(rec));
    }

    @Test
    void mintThenVerifyRoundTripsClaims() {
        UUID wallet = UUID.randomUUID();
        DelegationGrant g = grant(List.of("pay:x402", "read:data"), wallet);
        DelegationTokenService.MintedToken minted = service.mintToken(g.getId(), 600L);
        assertNotNull(minted.token());
        registerToken(minted, g);

        DelegationTokenService.VerifiedDelegation v =
            service.verify(minted.token(), "x402://premium-data", "pay:");
        assertEquals(g.getAgentId(), v.agentId());
        assertEquals("user-1", v.delegator());
        assertEquals("acme", v.tenantId());
        assertEquals("x402://premium-data", v.audience());
        assertTrue(v.scopes().contains("pay:x402"));
        assertEquals(wallet, v.walletId());
        // ttl was clamped to the requested 600s, under the grant's 3600 cap
        assertEquals(Instant.parse("2026-10-02T12:10:00Z"), v.expiresAt());
    }

    @Test
    void introspectReportsActiveThenInactiveAfterRevoke() {
        DelegationGrant g = grant(List.of("read:calendar"), null);
        DelegationTokenService.MintedToken minted = service.mintToken(g.getId(), null);
        registerToken(minted, g);

        Map<String, Object> active = service.introspect(minted.token());
        assertEquals(true, active.get("active"));
        assertEquals("delegation", active.get("token_use"));
        assertEquals("agent:" + g.getAgentId(), active.get("sub"));
        @SuppressWarnings("unchecked")
        Map<String, String> act = (Map<String, String>) active.get("act");
        assertEquals("user:user-1", act.get("sub"));

        service.revokeGrant(g.getId(), "test");
        // revokeGrant bulk-updates token rows; model that for the mock
        DelegationTokenRecord revoked = new DelegationTokenRecord();
        revoked.setStatus(DelegationStatus.REVOKED);
        when(tokenRepository.findById(minted.jti())).thenReturn(Optional.of(revoked));

        Map<String, Object> inactive = service.introspect(minted.token());
        assertEquals(false, inactive.get("active"));
        assertEquals("TOKEN_REVOKED", inactive.get("reason"));
    }

    @Test
    void wrongAudienceFailsClosed() {
        DelegationGrant g = grant(List.of("read:calendar"), null);
        DelegationTokenService.MintedToken minted = service.mintToken(g.getId(), null);
        registerToken(minted, g);
        DelegationException e = assertThrows(DelegationException.class,
            () -> service.verify(minted.token(), "x402://other-service", null));
        assertEquals("AUDIENCE_MISMATCH", e.getCode());
    }

    @Test
    void tamperedTokenFailsSignature() {
        DelegationGrant g = grant(List.of("read:calendar"), null);
        DelegationTokenService.MintedToken minted = service.mintToken(g.getId(), null);
        String tampered = minted.token().substring(0, minted.token().length() - 4) + "AAAA";
        DelegationException e = assertThrows(DelegationException.class,
            () -> service.verifySignatureAndClaims(tampered));
        assertEquals("BAD_SIGNATURE", e.getCode());
    }

    @Test
    void payScopeWithoutWalletIsRejected() {
        DelegationException e = assertThrows(DelegationException.class, () ->
            service.createGrant(UUID.randomUUID(), "user-1", "x402://x",
                List.of("pay:x402"), 0, Instant.parse("2026-10-03T00:00:00Z"), null, null));
        assertEquals("WALLET_REQUIRED", e.getCode());
    }

    @Test
    void spendCapRequiresAPayScope() {
        DelegationException e = assertThrows(DelegationException.class, () ->
            service.createGrant(UUID.randomUUID(), "user-1", "x402://x",
                List.of("read:x"), 0, Instant.parse("2026-10-03T00:00:00Z"), null, 1000L));
        assertEquals("SPEND_CAP_NEEDS_PAY", e.getCode());
    }

    @Test
    void recordSpendAccumulatesAndCapTrips() {
        DelegationGrant g = grant(List.of("pay:x402"), UUID.randomUUID(), 1000L);
        DelegationTokenService.MintedToken minted = service.mintToken(g.getId(), null);
        registerToken(minted, g);

        service.recordSpend(minted.jti(), 400);
        service.recordSpend(minted.jti(), 400);
        assertEquals(800, g.getSpentMinor());

        DelegationException e = assertThrows(DelegationException.class,
            () -> service.recordSpend(minted.jti(), 300));
        assertEquals("SPEND_CAP_EXCEEDED", e.getCode());
        // The failed reservation must NOT have been applied.
        assertEquals(800, g.getSpentMinor());
    }

    @Test
    void releaseSpendFreesCapAndClampsAtZero() {
        DelegationGrant g = grant(List.of("pay:x402"), UUID.randomUUID(), 1000L);
        DelegationTokenService.MintedToken minted = service.mintToken(g.getId(), null);
        registerToken(minted, g);

        service.recordSpend(minted.jti(), 700);
        service.releaseSpend(minted.jti(), 700);
        assertEquals(0, g.getSpentMinor());
        // A release larger than what was recorded clamps at zero rather
        // than throwing or going negative - the release path must never fail.
        service.releaseSpend(minted.jti(), 500);
        assertEquals(0, g.getSpentMinor());
    }

    @Test
    void recordSpendRejectsRevokedToken() {
        DelegationGrant g = grant(List.of("pay:x402"), UUID.randomUUID(), 1000L);
        DelegationTokenService.MintedToken minted = service.mintToken(g.getId(), null);
        DelegationTokenRecord revoked = new DelegationTokenRecord();
        revoked.setStatus(DelegationStatus.REVOKED);
        when(tokenRepository.findById(minted.jti())).thenReturn(Optional.of(revoked));

        DelegationException e = assertThrows(DelegationException.class,
            () -> service.recordSpend(minted.jti(), 100));
        assertEquals("TOKEN_REVOKED", e.getCode());
        assertEquals(0, g.getSpentMinor());
    }

    @Test
    void grantExpiredRejectsMint() {
        DelegationGrant g = grant(List.of("read:x"), null);
        g.getClass(); // grant is ACTIVE but expiresAt is in the future
        // Force expiry by backing-date the grant
        g.setExpiresAt(Instant.parse("2026-10-01T00:00:00Z"));
        assertThrows(DelegationException.class, () -> service.mintToken(g.getId(), null));
    }

    @Test
    void tokenNeverOutlivesGrant() {
        DelegationGrant g = grant(List.of("read:x"), null);
        g.setExpiresAt(Instant.parse("2026-10-02T12:30:00Z")); // grant dies in 30min
        DelegationTokenService.MintedToken minted = service.mintToken(g.getId(), 3600L);
        assertEquals(Instant.parse("2026-10-02T12:30:00Z"), minted.expiresAt());
    }
}
