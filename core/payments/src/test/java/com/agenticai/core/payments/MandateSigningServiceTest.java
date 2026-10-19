package com.agenticai.core.payments;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.crypto.EnvelopeEncryptionService;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link MandateSigningService} + {@link MandateCanonicalizer} round-trip:
 * canonicalize -> sign -> verify passes; tampered canonical JSON or a wrong
 * key id fails. The envelope encryptor is mocked to identity so this test
 * exercises real Ed25519 (JDK built-in) end to end.
 */
class MandateSigningServiceTest {

    private PaymentSigningKeyRepository keyRepository;
    private MandateSigningService signingService;
    private final MandateCanonicalizer canonicalizer = new MandateCanonicalizer();

    @BeforeEach
    void setUp() {
        keyRepository = mock(PaymentSigningKeyRepository.class);
        EnvelopeEncryptionService encryption = mock(EnvelopeEncryptionService.class);
        when(encryption.encrypt(anyString())).thenAnswer(inv -> "enc:" + inv.getArgument(0));
        when(encryption.decrypt(anyString())).thenAnswer(inv -> ((String) inv.getArgument(0)).substring(4));
        signingService = new MandateSigningService(keyRepository, encryption);
    }

    private PaymentSigningKey activeKey() {
        PaymentSigningKey key = signingService.activeKey();
        when(keyRepository.findByKeyId(key.getKeyId())).thenReturn(Optional.of(key));
        return key;
    }

    @Test
    void generatesTheKeyLazily() {
        PaymentSigningKey[] persisted = new PaymentSigningKey[1];
        when(keyRepository.findFirstByStatusOrderByCreatedAtDesc("ACTIVE")).thenReturn(Optional.empty());
        when(keyRepository.save(org.mockito.ArgumentMatchers.any())).thenAnswer(inv -> persisted[0] = inv.getArgument(0));

        PaymentSigningKey key = signingService.activeKey();

        assertTrue(key.getKeyId().startsWith("psk_"));
        // The private half must be stored ONLY as ciphertext.
        assertTrue(persisted[0].getPrivateKeyEncrypted().startsWith("enc:"));
        assertTrue(key.getPublicKey().length > 0);
    }

    @Test
    void signThenVerifyRoundTrips() {
        when(keyRepository.findFirstByStatusOrderByCreatedAtDesc("ACTIVE")).thenReturn(Optional.empty());
        when(keyRepository.save(org.mockito.ArgumentMatchers.any())).thenAnswer(inv -> inv.getArgument(0));
        PaymentSigningKey key = activeKey();

        UUID mandateId = UUID.randomUUID();
        String canonical = canonicalizer.canonicalize(
            mandateId, "tenant_a", UUID.randomUUID(), "user-1", MandateScope.COLLECT,
            50_000, 200_000, 1_000_000, java.util.List.of("a@okhdfc"), null,
            MandateApprovalPolicy.ALWAYS, null,
            Instant.parse("2026-09-26T00:00:00Z"), Instant.parse("2026-12-31T00:00:00Z"));
        byte[] signature = signingService.sign(key, canonical);

        PaymentMandate mandate = new PaymentMandate();
        mandate.setId(mandateId);
        mandate.setCanonicalJson(canonical);
        mandate.setSignature(signature);
        mandate.setKeyId(key.getKeyId());

        assertTrue(signingService.verify(mandate));
    }

    @Test
    void tamperedCanonicalJsonFailsVerification() {
        when(keyRepository.findFirstByStatusOrderByCreatedAtDesc("ACTIVE")).thenReturn(Optional.empty());
        when(keyRepository.save(org.mockito.ArgumentMatchers.any())).thenAnswer(inv -> inv.getArgument(0));
        PaymentSigningKey key = activeKey();

        String canonical = canonicalizer.canonicalize(
            UUID.randomUUID(), "tenant_a", UUID.randomUUID(), "user-1", MandateScope.COLLECT,
            50_000, 200_000, 1_000_000, null, null,
            MandateApprovalPolicy.NEVER, null, Instant.now(), Instant.now().plusSeconds(60));
        byte[] signature = signingService.sign(key, canonical);

        PaymentMandate mandate = new PaymentMandate();
        mandate.setId(UUID.randomUUID());
        // The attack: bump a cap inside the stored document post-sign.
        mandate.setCanonicalJson(canonical.replace("50000", "5000000"));
        mandate.setSignature(signature);
        mandate.setKeyId(key.getKeyId());

        assertFalse(signingService.verify(mandate));
    }

    @Test
    void unknownKeyIdFailsClosed() {
        PaymentMandate mandate = new PaymentMandate();
        mandate.setId(UUID.randomUUID());
        mandate.setCanonicalJson("{}");
        mandate.setSignature(new byte[64]);
        mandate.setKeyId("psk_nope");
        when(keyRepository.findByKeyId("psk_nope")).thenReturn(Optional.empty());

        assertFalse(signingService.verify(mandate));
    }

    @Test
    void canonicalDocumentIsDeterministicAndAp2Shaped() {
        UUID id = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
        UUID agent = UUID.fromString("00000000-0000-0000-0000-0000000000bb");
        String a = canonicalizer.canonicalize(
            id, "tenant_a", agent, "user-1", MandateScope.BOTH,
            1_000, 10_000, 100_000, java.util.List.of("b@ybl", "a@okhdfc"), java.util.List.of("z", "a"),
            MandateApprovalPolicy.ABOVE_THRESHOLD, 5_000L,
            Instant.parse("2026-09-26T00:00:00Z"), Instant.parse("2026-10-26T00:00:00Z"));
        // Same inputs -> byte-identical document (signature determinism).
        String b = canonicalizer.canonicalize(
            id, "tenant_a", agent, "user-1", MandateScope.BOTH,
            1_000, 10_000, 100_000, java.util.List.of("b@ybl", "a@okhdfc"), java.util.List.of("z", "a"),
            MandateApprovalPolicy.ABOVE_THRESHOLD, 5_000L,
            Instant.parse("2026-09-26T00:00:00Z"), Instant.parse("2026-10-26T00:00:00Z"));
        assertEquals(a, b);
        assertTrue(a.contains("\"version\":\"vegaduta-upi-mandate/1\""));
        assertTrue(a.contains("\"mandate_id\":\"" + id + "\""));
        // Allow-lists are sorted in the canonical form.
        assertTrue(a.indexOf("\"a@okhdfc\"") < a.indexOf("\"b@ybl\""));
        assertEquals(64, canonicalizer.canonicalHash(a).length());
    }
}
