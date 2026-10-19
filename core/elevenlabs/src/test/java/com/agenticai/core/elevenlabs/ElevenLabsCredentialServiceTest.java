package com.agenticai.core.elevenlabs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.crypto.EncryptionService;
import com.agenticai.core.elevenlabs.dto.ElevenLabsCredentialActivationRequest;
import com.agenticai.core.elevenlabs.dto.ElevenLabsCredentialResponse;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ElevenLabsCredentialService} covering BYOK
 * activation with validation, credential lifecycle (activate/deactivate), and
 * plaintext key resolution. Plain unit tests with mocked repository,
 * encryption service, and validator - no Spring context.
 */
class ElevenLabsCredentialServiceTest {

    private ElevenLabsCredentialRepository repository;
    private EncryptionService encryptionService;
    private ElevenLabsCredentialValidator validator;
    private ElevenLabsCredentialService service;

    @BeforeEach
    void setUp() {
        repository = mock(ElevenLabsCredentialRepository.class);
        encryptionService = mock(EncryptionService.class);
        validator = mock(ElevenLabsCredentialValidator.class);
        service = new ElevenLabsCredentialService(repository, encryptionService, validator);
    }

    // ------------------------------------------------------------------
    // activate - create new credential with validation
    // ------------------------------------------------------------------

    @Test
    void activateCreatesNewCredentialWhenValidationPasses() {
        String plainKey = "test-api-key-123";
        String encryptedKey = "encrypted::test-api-key-123";
        ElevenLabsCredentialActivationRequest request = new ElevenLabsCredentialActivationRequest(plainKey);

        when(repository.findFirstByOrderByCreatedAtAsc()).thenReturn(Optional.empty());
        when(encryptionService.encrypt(plainKey)).thenReturn(encryptedKey);
        // Validator does not throw

        ElevenLabsCredential credential = new ElevenLabsCredential(encryptedKey);
        when(repository.save(any(ElevenLabsCredential.class))).thenReturn(credential);

        ElevenLabsCredentialResponse response = service.activate(request);

        assertNotNull(response);
        assertEquals(ElevenLabsCredentialStatus.ACTIVE, response.status());
        verify(validator, times(1)).validate(plainKey);
        verify(encryptionService, times(1)).encrypt(plainKey);
        verify(repository, times(1)).save(any(ElevenLabsCredential.class));
    }

    @Test
    void activateReactivatesExistingCredentialWithNewKey() {
        String oldEncryptedKey = "encrypted::old-key";
        String newPlainKey = "new-api-key-456";
        String newEncryptedKey = "encrypted::new-key";
        ElevenLabsCredentialActivationRequest request = new ElevenLabsCredentialActivationRequest(newPlainKey);

        ElevenLabsCredential existing = new ElevenLabsCredential(oldEncryptedKey);
        when(repository.findFirstByOrderByCreatedAtAsc()).thenReturn(Optional.of(existing));
        when(encryptionService.encrypt(newPlainKey)).thenReturn(newEncryptedKey);
        when(repository.save(existing)).thenReturn(existing);

        ElevenLabsCredentialResponse response = service.activate(request);

        assertNotNull(response);
        assertEquals(ElevenLabsCredentialStatus.ACTIVE, response.status());
        verify(validator, times(1)).validate(newPlainKey);
        verify(repository, times(1)).save(existing);
    }

    @Test
    void activateThrowsValidationExceptionAndPersistsNothingOnInvalidKey() {
        String invalidKey = "bad-key";
        ElevenLabsCredentialActivationRequest request = new ElevenLabsCredentialActivationRequest(invalidKey);

        when(repository.findFirstByOrderByCreatedAtAsc()).thenReturn(Optional.empty());
        doThrow(new ElevenLabsCredentialValidationException("Invalid API key format"))
            .when(validator).validate(invalidKey);

        ElevenLabsCredentialValidationException thrown = assertThrows(ElevenLabsCredentialValidationException.class,
            () -> service.activate(request));

        assertTrue(thrown.getMessage().contains("Invalid"));
        verify(repository, times(0)).save(any());
    }

    // ------------------------------------------------------------------
    // current - retrieve current credential
    // ------------------------------------------------------------------

    @Test
    void currentReturnsCredentialWhenOneExists() {
        ElevenLabsCredential credential = new ElevenLabsCredential("encrypted::key");
        when(repository.findFirstByOrderByCreatedAtAsc()).thenReturn(Optional.of(credential));

        Optional<ElevenLabsCredentialResponse> current = service.current();

        assertTrue(current.isPresent());
        assertNotNull(current.get());
    }

    @Test
    void currentReturnsEmptyWhenNoneConfigured() {
        when(repository.findFirstByOrderByCreatedAtAsc()).thenReturn(Optional.empty());

        Optional<ElevenLabsCredentialResponse> current = service.current();

        assertFalse(current.isPresent());
    }

    // ------------------------------------------------------------------
    // isActive - check if credential is active
    // ------------------------------------------------------------------

    @Test
    void isActiveReturnsTrueWhenCredentialIsActive() {
        ElevenLabsCredential credential = new ElevenLabsCredential("encrypted::key");
        when(repository.findFirstByOrderByCreatedAtAsc()).thenReturn(Optional.of(credential));

        assertTrue(service.isActive());
    }

    @Test
    void isActiveReturnsFalseWhenNothingConfigured() {
        when(repository.findFirstByOrderByCreatedAtAsc()).thenReturn(Optional.empty());

        assertFalse(service.isActive());
    }

    @Test
    void isActiveReturnsFalseWhenCredentialIsInactive() {
        ElevenLabsCredential credential = new ElevenLabsCredential("encrypted::key");
        credential.deactivate();
        when(repository.findFirstByOrderByCreatedAtAsc()).thenReturn(Optional.of(credential));

        assertFalse(service.isActive());
    }

    // ------------------------------------------------------------------
    // deactivate - deactivate credential
    // ------------------------------------------------------------------

    @Test
    void deactivateMarksCredentialAsInactive() {
        ElevenLabsCredential credential = new ElevenLabsCredential("encrypted::key");
        when(repository.findFirstByOrderByCreatedAtAsc()).thenReturn(Optional.of(credential));
        when(repository.save(credential)).thenReturn(credential);

        service.deactivate();

        assertEquals(ElevenLabsCredentialStatus.INACTIVE, credential.getStatus());
        verify(repository, times(1)).save(credential);
    }

    @Test
    void deactivateIsNoOpWhenNothingConfigured() {
        when(repository.findFirstByOrderByCreatedAtAsc()).thenReturn(Optional.empty());

        service.deactivate(); // should not throw

        verify(repository, times(0)).save(any());
    }

    // ------------------------------------------------------------------
    // resolveActiveApiKey - resolve plaintext key for internal use
    // ------------------------------------------------------------------

    @Test
    void resolveActiveApiKeyReturnsDecryptedKeyWhenCredentialIsActive() {
        String encryptedKey = "encrypted::key";
        String plainKey = "test-api-key-123";
        ElevenLabsCredential credential = new ElevenLabsCredential(encryptedKey);
        when(repository.findFirstByOrderByCreatedAtAsc()).thenReturn(Optional.of(credential));
        when(encryptionService.decrypt(encryptedKey)).thenReturn(plainKey);

        String resolved = service.resolveActiveApiKey();

        assertEquals(plainKey, resolved);
        verify(encryptionService, times(1)).decrypt(encryptedKey);
    }

    @Test
    void resolveActiveApiKeyThrowsWhenNoCredentialConfigured() {
        when(repository.findFirstByOrderByCreatedAtAsc()).thenReturn(Optional.empty());

        assertThrows(ElevenLabsNotConfiguredException.class, () -> service.resolveActiveApiKey());
    }

    @Test
    void resolveActiveApiKeyThrowsWhenCredentialIsInactive() {
        ElevenLabsCredential credential = new ElevenLabsCredential("encrypted::key");
        credential.deactivate();
        when(repository.findFirstByOrderByCreatedAtAsc()).thenReturn(Optional.of(credential));

        assertThrows(ElevenLabsNotConfiguredException.class, () -> service.resolveActiveApiKey());
    }
}
