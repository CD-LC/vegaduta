package com.agenticai.core.crypto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Base64;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class EncryptionServiceTest {

    private static final String TEST_KEY = Base64.getEncoder().encodeToString(new byte[32]);

    private EncryptionService newService(String key) {
        return newService(key, "");
    }

    private EncryptionService newService(String key, String previousKey) {
        EncryptionService service = new EncryptionService(key, previousKey);
        service.init();
        return service;
    }

    @Test
    void encryptThenDecryptReturnsOriginalPlaintext() {
        EncryptionService service = newService(TEST_KEY);
        String plaintext = "sk-ant-api03-super-secret-key";

        String ciphertext = service.encrypt(plaintext);
        String decrypted = service.decrypt(ciphertext);

        assertEquals(plaintext, decrypted);
        assertNotEquals(plaintext, ciphertext, "ciphertext must not equal the plaintext");
    }

    @Test
    void encryptingTheSamePlaintextTwiceProducesDifferentCiphertext() {
        EncryptionService service = newService(TEST_KEY);
        String plaintext = "sk-ant-api03-super-secret-key";

        Set<String> ciphertexts = new HashSet<>();
        ciphertexts.add(service.encrypt(plaintext));
        ciphertexts.add(service.encrypt(plaintext));

        assertEquals(2, ciphertexts.size(), "random IV per call means ciphertext should differ each time");
    }

    @Test
    void missingKeyFailsFastAtStartup() {
        EncryptionService service = new EncryptionService("", "");
        assertThrows(IllegalStateException.class, service::init);
    }

    @Test
    void wrongLengthKeyFailsFastAtStartup() {
        String shortKey = Base64.getEncoder().encodeToString(new byte[16]); // AES-128, not AES-256
        EncryptionService service = new EncryptionService(shortKey, "");
        assertThrows(IllegalStateException.class, service::init);
    }

    @Test
    void aValueThatIsNotCiphertextIsAClearErrorNotARawJdkException() {
        EncryptionService service = newService(TEST_KEY);
        // A plaintext placeholder (not base64), valid base64 that is too short to hold an IV, and empty input.
        for (String notCiphertext : new String[] {"unused-mock-key", "dGlueQ==", ""}) {
            IllegalStateException e = assertThrows(IllegalStateException.class, () -> service.decrypt(notCiphertext),
                "input: '" + notCiphertext + "'");
            assertEquals("Failed to decrypt secret", e.getMessage());
        }
    }

    @Test
    void aValueThatIsNotCiphertextIsAlsoAClearErrorDuringKeyRotation() {
        EncryptionService service = newService(TEST_KEY, Base64.getEncoder().encodeToString(new byte[32]));
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> service.decrypt("unused-mock-key"));
        assertEquals("Failed to decrypt secret with either the current or previous encryption key", e.getMessage());
    }
}
