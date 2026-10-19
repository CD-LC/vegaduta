package com.agenticai.core.mcp.oauth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.agenticai.core.crypto.EncryptionService;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OAuthStateCodecTest {

    private static final long TTL = 600;

    private OAuthProviderTestSupport.MutableClock clock;
    private EncryptionService encryption;
    private OAuthStateCodec codec;

    @BeforeEach
    void setUp() {
        clock = new OAuthProviderTestSupport.MutableClock(Instant.parse("2026-10-04T10:00:00Z"));
        encryption = OAuthProviderTestSupport.encryption((byte) 7);
        codec = new OAuthStateCodec(encryption, clock, TTL);
    }

    private String mint() {
        return codec.encode("github", "tenant_a", "user-1", "verifier-123", "https://app.example.com/tools?tab=oauth&x=1");
    }

    @Test
    void roundTripsTheTenantUserProviderVerifierAndReturnUrl() {
        OAuthStateCodec.Payload p = codec.decode(mint(), "github");

        assertEquals("github", p.providerId());
        assertEquals("tenant_a", p.tenantId());
        assertEquals("user-1", p.userId());
        assertEquals("verifier-123", p.codeVerifier());
        assertEquals("https://app.example.com/tools?tab=oauth&x=1", p.returnUrl());
        assertEquals(clock.instant().getEpochSecond(), p.issuedAtEpochSeconds());
    }

    @Test
    void valuesContainingTheOldDelimitersCannotInjectOrShiftFields() {
        String state = codec.encode("github", "tenant|evil", "user.with.dots", "ver|ifier.x", "https://a.example/p?q=a|b.c");
        OAuthStateCodec.Payload p = codec.decode(state, "github");

        assertEquals("tenant|evil", p.tenantId());
        assertEquals("user.with.dots", p.userId());
        assertEquals("ver|ifier.x", p.codeVerifier());
        assertEquals("https://a.example/p?q=a|b.c", p.returnUrl());
    }

    @Test
    void everyMintedStateCarriesAFreshNonce() {
        OAuthStateCodec.Payload a = codec.decode(mint(), "github");
        OAuthStateCodec.Payload b = codec.decode(mint(), "github");
        assertNotEquals(a.nonce(), b.nonce());
    }

    @Test
    void aStateIsAcceptedRightUpToItsLifetimeAndRejectedAfter() {
        String state = mint();
        clock.advance(Duration.ofSeconds(TTL));
        codec.decode(state, "github"); // exactly at the limit: still valid

        clock.advance(Duration.ofSeconds(1));
        OAuthStateException e = assertThrows(OAuthStateException.class, () -> codec.decode(state, "github"));
        assertEquals(OAuthStateException.EXPIRED, e.reason());
    }

    @Test
    void aStateIssuedInTheFutureIsRejected() {
        String state = mint();
        // the verifying node's clock is far behind the issuing one
        OAuthProviderTestSupport.MutableClock behind = new OAuthProviderTestSupport.MutableClock(clock.instant().minus(Duration.ofHours(1)));
        OAuthStateCodec other = new OAuthStateCodec(encryption, behind, TTL);

        OAuthStateException e = assertThrows(OAuthStateException.class, () -> other.decode(state, "github"));
        assertEquals(OAuthStateException.INVALID, e.reason());
    }

    @Test
    void aTamperedOrForgedStateFailsToDecrypt() {
        String state = mint();
        char flipped = state.charAt(10) == 'A' ? 'B' : 'A';
        String tampered = state.substring(0, 10) + flipped + state.substring(11);

        assertEquals(OAuthStateException.INVALID,
            assertThrows(OAuthStateException.class, () -> codec.decode(tampered, "github")).reason());
        assertEquals(OAuthStateException.INVALID,
            assertThrows(OAuthStateException.class, () -> codec.decode("not-a-state", "github")).reason());
        assertEquals(OAuthStateException.INVALID,
            assertThrows(OAuthStateException.class, () -> codec.decode("", "github")).reason());
        assertEquals(OAuthStateException.INVALID,
            assertThrows(OAuthStateException.class, () -> codec.decode(null, "github")).reason());
    }

    @Test
    void aStateEncryptedWithAnotherKeyIsRejected() {
        OAuthStateCodec foreign = new OAuthStateCodec(OAuthProviderTestSupport.encryption((byte) 9), clock, TTL);
        String foreignState = foreign.encode("github", "tenant_a", "user-1", "v", "");

        assertEquals(OAuthStateException.INVALID,
            assertThrows(OAuthStateException.class, () -> codec.decode(foreignState, "github")).reason());
    }

    @Test
    void aWellFormedEncryptedPayloadOfTheWrongShapeIsMalformedNotAnError500() {
        String wrongShape = encryption.encrypt("v1.only.three");
        assertEquals(OAuthStateException.INVALID,
            assertThrows(OAuthStateException.class, () -> codec.decode(wrongShape, "github")).reason());
        String notBase64 = encryption.encrypt("v1.@@.@@.@@.nonce.123.@@.@@");
        assertEquals(OAuthStateException.INVALID,
            assertThrows(OAuthStateException.class, () -> codec.decode(notBase64, "github")).reason());
    }

    @Test
    void aStateMintedForOneProviderCannotCompleteAnother() {
        String state = mint();
        OAuthStateException e = assertThrows(OAuthStateException.class, () -> codec.decode(state, "slack"));
        assertEquals(OAuthStateException.PROVIDER_MISMATCH, e.reason());
    }

    @Test
    void aStateCanBeConsumedExactlyOnce() {
        OAuthStateCodec.Payload p = codec.decode(mint(), "github");
        codec.consume(p);

        OAuthStateException e = assertThrows(OAuthStateException.class, () -> codec.consume(p));
        assertEquals(OAuthStateException.REPLAYED, e.reason());
    }

    @Test
    void consumedNoncesAreForgottenOnceTheStateCouldNoLongerBeValidAnyway() {
        OAuthStateCodec.Payload p = codec.decode(mint(), "github");
        codec.consume(p);

        clock.advance(Duration.ofSeconds(TTL + 120));
        // the replay window is closed by expiry, so the bookkeeping entry may go; consuming a fresh state still works
        codec.consume(codec.decode(mint(), "github"));
    }
}
