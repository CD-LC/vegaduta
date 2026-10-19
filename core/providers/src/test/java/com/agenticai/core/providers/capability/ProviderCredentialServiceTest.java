package com.agenticai.core.providers.capability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.crypto.EncryptionService;
import com.agenticai.core.providers.ProviderStatus;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link ProviderCredentialRepository} has no {@code @DataJpaTest}/repository-level
 * test to mirror (no repository in this codebase does - every credential
 * repository, including {@code LlmProviderCredentialRepository}, is exercised
 * only indirectly through its service's Mockito-based unit tests, see {@code
 * ProviderServiceTest}). This suite follows that same convention for {@link
 * ProviderCredentialService} instead.
 */
class ProviderCredentialServiceTest {

    private ProviderCredentialRepository repository;
    private EncryptionService encryptionService;
    private ProviderCredentialValidator validator;
    private ProviderCredentialService service;

    @BeforeEach
    void setUp() {
        repository = mock(ProviderCredentialRepository.class);
        encryptionService = mock(EncryptionService.class);
        validator = mock(ProviderCredentialValidator.class);
        when(validator.providerKey()).thenReturn("TYPESAFE");
        when(validator.displayName()).thenReturn("TypeSafe Jev");
        when(validator.capabilities()).thenReturn(List.of("DECISION"));
        service = new ProviderCredentialService(repository, encryptionService, List.of(validator));
    }

    @Test
    void saveEncryptsPlaintextBeforePersisting() {
        when(encryptionService.encrypt("plaintext-secret")).thenReturn("cipher-text");
        when(repository.findByProviderKey("ELEVENLABS")).thenReturn(Optional.empty());
        when(repository.save(org.mockito.ArgumentMatchers.any(ProviderCredential.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        ProviderCredential saved = service.save(
            "ELEVENLABS", List.of("TTS"), "plaintext-secret", null);

        assertEquals("cipher-text", saved.getEncryptedSecret());
        assertEquals(ProviderStatus.ACTIVE, saved.getStatus());
        verify(encryptionService, never()).encrypt("cipher-text");
    }

    @Test
    void saveReactivatesExistingRowInsteadOfInsertingDuplicate() {
        ProviderCredential existing = new ProviderCredential(
            "SARVAM", List.of("STT"), "old-cipher", ProviderStatus.INACTIVE, null);
        when(repository.findByProviderKey("SARVAM")).thenReturn(Optional.of(existing));
        when(encryptionService.encrypt("new-secret")).thenReturn("new-cipher");

        ProviderCredential result = service.save("SARVAM", List.of("STT", "TTS"), "new-secret", null);

        assertEquals(existing, result);
        assertEquals("new-cipher", result.getEncryptedSecret());
        assertEquals(ProviderStatus.ACTIVE, result.getStatus());
        assertTrue(result.getCapabilities().containsAll(List.of("STT", "TTS")));
        verify(repository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void resolveDecryptedSecretReturnsEmptyForInactiveCredential() {
        ProviderCredential inactive = new ProviderCredential(
            "E2B", List.of("CODE_EXEC"), "cipher", ProviderStatus.INACTIVE, null);
        when(repository.findByProviderKey("E2B")).thenReturn(Optional.of(inactive));

        Optional<String> resolved = service.resolveDecryptedSecret("E2B");

        assertTrue(resolved.isEmpty());
        verify(encryptionService, never()).decrypt(anyString());
    }

    @Test
    void resolveDecryptedSecretDecryptsActiveCredential() {
        ProviderCredential active = new ProviderCredential(
            "ELEVENLABS", List.of("TTS"), "cipher", ProviderStatus.ACTIVE, null);
        when(repository.findByProviderKey("ELEVENLABS")).thenReturn(Optional.of(active));
        when(encryptionService.decrypt("cipher")).thenReturn("plaintext");

        Optional<String> resolved = service.resolveDecryptedSecret("ELEVENLABS");

        assertEquals(Optional.of("plaintext"), resolved);
    }

    @Test
    void deactivateMarksExistingRowInactiveWithoutDeleting() {
        ProviderCredential active = new ProviderCredential(
            "SARVAM", List.of("TTS"), "cipher", ProviderStatus.ACTIVE, null);
        when(repository.findByProviderKey("SARVAM")).thenReturn(Optional.of(active));

        service.deactivate("SARVAM");

        assertEquals(ProviderStatus.INACTIVE, active.getStatus());
        verify(repository, never()).deleteById(org.mockito.ArgumentMatchers.any());
    }

    // --- activate(): the validator-dispatch path the /api/capabilities/*
    // credential endpoints and the /api/jev shim share ----------------------

    @Test
    void activateValidatesThenPersistsWithTheValidatorsCapabilities() {
        when(encryptionService.encrypt("plain-key")).thenReturn("cipher-key");
        when(repository.findByProviderKey("TYPESAFE")).thenReturn(Optional.empty());
        when(repository.save(org.mockito.ArgumentMatchers.any(ProviderCredential.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        ProviderCredential saved = service.activate("TYPESAFE", "plain-key", null);

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(validator, repository);
        order.verify(validator).validate("plain-key");
        order.verify(repository).save(org.mockito.ArgumentMatchers.any(ProviderCredential.class));
        assertEquals("TYPESAFE", saved.getProviderKey());
        assertEquals(List.of("DECISION"), saved.getCapabilities());
        assertEquals(ProviderStatus.ACTIVE, saved.getStatus());
        assertEquals("cipher-key", saved.getEncryptedSecret());
    }

    @Test
    void activateRejectsWhenNoValidatorKnowsTheProvider() {
        org.junit.jupiter.api.Assertions.assertThrows(
            UnknownCapabilityException.class,
            () -> service.activate("NOPE", "key", null));
        verify(repository, never()).save(org.mockito.ArgumentMatchers.any());
        verify(encryptionService, never()).encrypt(anyString());
    }

    @Test
    void activatePersistsNothingWhenTheKeyIsRejected() {
        org.mockito.Mockito.doThrow(new CapabilityCredentialValidationException("rejected"))
            .when(validator).validate("bad-key");

        org.junit.jupiter.api.Assertions.assertThrows(
            CapabilityCredentialValidationException.class,
            () -> service.activate("TYPESAFE", "bad-key", null));
        verify(repository, never()).save(org.mockito.ArgumentMatchers.any());
        verify(encryptionService, never()).encrypt(anyString());
    }
}
