package com.agenticai.core.omnichannel.twilio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.crypto.EncryptionService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** {@link VoiceRelayTicketService}: mint/verify round-trip plus every rejection path. */
class VoiceRelayTicketServiceTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    private EncryptionService encryptionService;
    private VoiceRelayTicketService service;

    @BeforeEach
    void setUp() {
        encryptionService = mock(EncryptionService.class);
        service = new VoiceRelayTicketService(encryptionService, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void mintEncryptsThePipeJoinedFieldsWithAFiveMinuteExpiry() {
        when(encryptionService.encrypt(anyString())).thenReturn("cipher");

        service.mint("AC1", "CA2", "+15551234567");

        long expectedExpiry = NOW.plus(VoiceRelayTicketService.TTL).getEpochSecond();
        verify(encryptionService)
                .encrypt("AC1|CA2|+15551234567|" + expectedExpiry);
    }

    @Test
    void mintStripsSeparatorCharactersOutOfCallerControlledFields() {
        when(encryptionService.encrypt(anyString())).thenReturn("cipher");

        service.mint("AC|1", "CA2", null);

        verify(encryptionService)
                .encrypt(
                        org.mockito.ArgumentMatchers.contains(
                                "AC1|CA2||"));
    }

    @Test
    void verifyReturnsTheAdmissionForAValidUnexpiredTicket() {
        long future = NOW.plusSeconds(60).getEpochSecond();
        when(encryptionService.decrypt("ticket-1"))
                .thenReturn("AC1|CA2|+15551234567|" + future);

        Optional<VoiceRelayTicketService.Admission> admission = service.verify("ticket-1");

        assertTrue(admission.isPresent());
        assertEquals("AC1", admission.get().accountSid());
        assertEquals("CA2", admission.get().callSid());
        assertEquals("+15551234567", admission.get().from());
    }

    @Test
    void verifyRejectsNullBlankAndOversizedInput() {
        assertTrue(service.verify(null).isEmpty());
        assertTrue(service.verify("   ").isEmpty());
        assertTrue(service.verify("x".repeat(1025)).isEmpty());
    }

    @Test
    void verifyRejectsAnExpiredTicket() {
        long past = NOW.minusSeconds(1).getEpochSecond();
        when(encryptionService.decrypt("old")).thenReturn("AC1|CA2|+1555|" + past);
        assertTrue(service.verify("old").isEmpty());
    }

    @Test
    void verifyRejectsMalformedPayloads() {
        when(encryptionService.decrypt("few")).thenReturn("AC1|CA2");
        assertTrue(service.verify("few").isEmpty());

        when(encryptionService.decrypt("many")).thenReturn("a|b|c|d|e");
        assertTrue(service.verify("many").isEmpty());

        when(encryptionService.decrypt("blank-sid")).thenReturn("|CA2|+1555|99999999999");
        assertTrue(service.verify("blank-sid").isEmpty());

        when(encryptionService.decrypt("bad-expiry")).thenReturn("AC1|CA2|+1555|nan");
        assertTrue(service.verify("bad-expiry").isEmpty());
    }

    @Test
    void verifySwallowsDecryptionFailures() {
        when(encryptionService.decrypt("garbage"))
                .thenThrow(new RuntimeException("not a ticket"));
        assertTrue(service.verify("garbage").isEmpty());
    }
}
