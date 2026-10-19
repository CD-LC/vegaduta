package com.agenticai.core.voice.realtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.crypto.EncryptionService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * The relay's ONLY authentication - if a forged or expired ticket verifies,
 * anyone who can open a WebSocket gets a free native-audio session on a
 * tenant's provider key. Mirrors VoiceRelayTicketService's guarantees.
 */
class RealtimeVoiceTicketServiceTest {

    /** EncryptionService parses its key in a package-private @PostConstruct, which only Spring would otherwise run. */
    private static EncryptionService encryption() {
        EncryptionService svc = new EncryptionService(
            Base64.getEncoder().encodeToString(new byte[32]), "");
        ReflectionTestUtils.invokeMethod(svc, "init");
        return svc;
    }

    private static RealtimeVoiceTicketService service(Clock clock) {
        return new RealtimeVoiceTicketService(encryption(), clock);
    }

    @Test
    void mintThenVerify_roundTripsEveryField() {
        RealtimeVoiceTicketService svc = service(Clock.systemUTC());
        UUID agentId = UUID.randomUUID();
        String ticket = svc.mint("tenant_acme", agentId, RealtimeProvider.GEMINI_LIVE, "user-1");

        Optional<RealtimeVoiceTicketService.Admission> adm = svc.verify(ticket);
        assertTrue(adm.isPresent());
        assertEquals("tenant_acme", adm.get().tenantSchema());
        assertEquals(agentId, adm.get().agentId());
        assertEquals(RealtimeProvider.GEMINI_LIVE, adm.get().provider());
        assertEquals("user-1", adm.get().subject());
    }

    @Test
    void verify_rejectsForgedBlankAndTruncated() {
        RealtimeVoiceTicketService svc = service(Clock.systemUTC());
        String ticket = svc.mint("t", UUID.randomUUID(), RealtimeProvider.OPENAI_REALTIME, "u");

        assertTrue(svc.verify(null).isEmpty());
        assertTrue(svc.verify("").isEmpty());
        assertTrue(svc.verify("not-a-ticket").isEmpty());
        assertTrue(svc.verify(ticket.substring(0, ticket.length() - 4)).isEmpty());
        // A ticket minted under a DIFFERENT key must not verify.
        EncryptionService otherEnc = new EncryptionService(
            Base64.getEncoder().encodeToString(fillKey((byte) 7)), "");
        ReflectionTestUtils.invokeMethod(otherEnc, "init");
        RealtimeVoiceTicketService otherSvc = new RealtimeVoiceTicketService(otherEnc, Clock.systemUTC());
        assertTrue(otherSvc.verify(ticket).isEmpty());
    }

    @Test
    void verify_rejectsExpired() {
        Instant t0 = Instant.parse("2026-09-24T00:00:00Z");
        RealtimeVoiceTicketService svc = service(Clock.fixed(t0, ZoneOffset.UTC));
        String ticket = svc.mint("t", UUID.randomUUID(), RealtimeProvider.GEMINI_LIVE, "u");

        // Same key material, clock 6 minutes past mint -> TTL (5m) expired.
        RealtimeVoiceTicketService later = new RealtimeVoiceTicketService(
            encryption(), Clock.fixed(t0.plus(Duration.ofMinutes(6)), ZoneOffset.UTC));
        assertTrue(later.verify(ticket).isEmpty(), "ticket past TTL must not verify");
    }

    @Test
    void verifyAndConsume_admitsOnceThenRefusesReplay() {
        RealtimeVoiceTicketService svc = service(Clock.systemUTC());
        UUID agentId = UUID.randomUUID();
        String ticket = svc.mint("tenant_acme", agentId, RealtimeProvider.GEMINI_LIVE, "user-1");

        Optional<RealtimeVoiceTicketService.Admission> first = svc.verifyAndConsume(ticket);
        assertTrue(first.isPresent());
        assertEquals("tenant_acme", first.get().tenantSchema());
        assertEquals(agentId, first.get().agentId());
        assertTrue(svc.verifyAndConsume(ticket).isEmpty(), "a used ticket must not admit a second session");
        // verify() alone never burns, so read-only callers are unaffected.
        assertTrue(svc.verify(ticket).isPresent());
    }

    @Test
    void verifyAndConsume_doesNotBurnATicketThatFailedVerification() {
        RealtimeVoiceTicketService svc = service(Clock.systemUTC());
        assertTrue(svc.verifyAndConsume("not-a-ticket").isEmpty());
        assertTrue(svc.verifyAndConsume(null).isEmpty());
    }

    @Test
    void twoTicketsForTheSameSessionAreDistinct() {
        RealtimeVoiceTicketService svc = service(Clock.systemUTC());
        UUID agentId = UUID.randomUUID();
        String a = svc.mint("t", agentId, RealtimeProvider.GEMINI_LIVE, "u");
        String b = svc.mint("t", agentId, RealtimeProvider.GEMINI_LIVE, "u");

        assertTrue(!a.equals(b), "AES-GCM uses a fresh IV, so tickets are not guessable from their fields");
        assertTrue(svc.verifyAndConsume(a).isPresent());
        assertTrue(svc.verifyAndConsume(b).isPresent());
    }

    private static byte[] fillKey(byte b) {
        byte[] k = new byte[32];
        java.util.Arrays.fill(k, b);
        return k;
    }
}
