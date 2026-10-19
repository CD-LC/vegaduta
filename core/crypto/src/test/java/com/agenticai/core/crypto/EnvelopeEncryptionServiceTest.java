package com.agenticai.core.crypto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Base64;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class EnvelopeEncryptionServiceTest {

    private static final String TEST_KEY = Base64.getEncoder().encodeToString(new byte[32]);

    private EnvelopeEncryptionService newService() {
        LocalKeyManagementProvider provider = new LocalKeyManagementProvider(TEST_KEY);
        provider.init();
        return new EnvelopeEncryptionService(provider);
    }

    @Test
    void encryptThenDecryptReturnsOriginalPlaintext() {
        EnvelopeEncryptionService service = newService();
        String plaintext = "sk-ant-api03-super-secret-key";

        String ciphertext = service.encrypt(plaintext);
        String decrypted = service.decrypt(ciphertext);

        assertEquals(plaintext, decrypted);
        assertNotEquals(plaintext, ciphertext, "ciphertext must not equal the plaintext");
    }

    @Test
    void encryptingTheSamePlaintextTwiceProducesDifferentCiphertextAndDifferentWrappedKey() {
        EnvelopeEncryptionService service = newService();
        String plaintext = "sk-ant-api03-super-secret-key";

        Set<String> ciphertexts = new HashSet<>();
        ciphertexts.add(service.encrypt(plaintext));
        ciphertexts.add(service.encrypt(plaintext));

        assertEquals(2, ciphertexts.size(),
            "random IV and a fresh random per-secret data key mean ciphertext should differ each time");
    }

    @Test
    void twoDifferentProviderInstancesWithTheSameKekCanDecryptEachOthersEnvelopes() {
        // Different LocalKeyManagementProvider instances constructed from the
        // same APP_ENCRYPTION_KEY value simulate two app instances/restarts
        // sharing config - an envelope encrypted by one must decrypt on the
        // other, since the KEK (not any in-memory provider state) is what
        // actually determines wrap/unwrap compatibility.
        LocalKeyManagementProvider providerA = new LocalKeyManagementProvider(TEST_KEY);
        providerA.init();
        LocalKeyManagementProvider providerB = new LocalKeyManagementProvider(TEST_KEY);
        providerB.init();

        String ciphertext = new EnvelopeEncryptionService(providerA).encrypt("secret-value");
        String decrypted = new EnvelopeEncryptionService(providerB).decrypt(ciphertext);

        assertEquals("secret-value", decrypted);
    }

    @Test
    void decryptFailsWithWrongKek() {
        EnvelopeEncryptionService service = newService();
        String ciphertext = service.encrypt("secret-value");

        String differentKey = Base64.getEncoder().encodeToString(new byte[]{
            1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16,
            17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32
        });
        LocalKeyManagementProvider wrongProvider = new LocalKeyManagementProvider(differentKey);
        wrongProvider.init();
        EnvelopeEncryptionService wrongService = new EnvelopeEncryptionService(wrongProvider);

        assertThrows(IllegalStateException.class, () -> wrongService.decrypt(ciphertext));
    }
}
