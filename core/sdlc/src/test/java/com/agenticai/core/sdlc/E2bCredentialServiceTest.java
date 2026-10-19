package com.agenticai.core.sdlc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.crypto.EncryptionService;
import com.agenticai.core.sdlc.dto.E2bCredentialActivationRequest;
import com.agenticai.core.sdlc.dto.E2bCredentialResponse;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link E2bCredentialService} covering BYOK activation,
 * credential lifecycle (activate/deactivate), and code-execution template
 * configuration. Plain unit tests with mocked repository and encryption
 * service - no Spring context, consistent with {@link
 * E2bSandboxControllerTest}.
 */
class E2bCredentialServiceTest {

    private E2bCredentialRepository repository;
    private EncryptionService encryptionService;
    private E2bCredentialService service;

    @BeforeEach
    void setUp() {
        repository = mock(E2bCredentialRepository.class);
        encryptionService = mock(EncryptionService.class);
        service = new E2bCredentialService(repository, encryptionService);
    }

    // ------------------------------------------------------------------
    // activate - create new credential
    // ------------------------------------------------------------------

    @Test
    void activateCreatesNewCredentialWhenNoneExists() {
        String plainKey = "test-api-key-123";
        String encryptedKey = "encrypted::test-api-key-123";
        E2bCredentialActivationRequest request = new E2bCredentialActivationRequest(plainKey);

        when(repository.findFirstByOrderByCreatedAtAsc()).thenReturn(Optional.empty());
        when(encryptionService.encrypt(plainKey)).thenReturn(encryptedKey);

        UUID credentialId = UUID.randomUUID();
        Instant now = Instant.now();
        E2bCredential saved = new E2bCredential(encryptedKey);
        saved.getId(); // trigger initialization
        when(repository.save(any(E2bCredential.class))).thenAnswer(inv -> {
            E2bCredential cred = inv.getArgument(0);
            cred.getId(); // ensure the saved instance gets an ID
            return cred;
        });

        E2bCredentialResponse response = service.activate(request);

        assertNotNull(response);
        assertEquals(E2bCredentialStatus.ACTIVE, response.status());
        verify(encryptionService, times(1)).encrypt(plainKey);
        verify(repository, times(1)).save(any(E2bCredential.class));
    }

    @Test
    void activateReactivatesExistingCredentialWithNewKey() {
        String oldEncryptedKey = "encrypted::old-key";
        String newPlainKey = "new-api-key-456";
        String newEncryptedKey = "encrypted::new-key";
        E2bCredentialActivationRequest request = new E2bCredentialActivationRequest(newPlainKey);

        UUID credentialId = UUID.randomUUID();
        Instant oldTime = Instant.parse("2026-08-01T00:00:00Z");
        E2bCredential existing = new E2bCredential(oldEncryptedKey);
        when(repository.findFirstByOrderByCreatedAtAsc()).thenReturn(Optional.of(existing));
        when(encryptionService.encrypt(newPlainKey)).thenReturn(newEncryptedKey);
        when(repository.save(existing)).thenReturn(existing);

        E2bCredentialResponse response = service.activate(request);

        assertNotNull(response);
        assertEquals(E2bCredentialStatus.ACTIVE, response.status());
        verify(repository, times(1)).save(existing);
    }

    // ------------------------------------------------------------------
    // current - retrieve current credential
    // ------------------------------------------------------------------

    @Test
    void currentReturnsCredentialWhenOneExists() {
        UUID credentialId = UUID.randomUUID();
        E2bCredential credential = new E2bCredential("encrypted::key");
        when(repository.findFirstByOrderByCreatedAtAsc()).thenReturn(Optional.of(credential));

        Optional<E2bCredentialResponse> current = service.current();

        assertTrue(current.isPresent());
        assertNotNull(current.get());
    }

    @Test
    void currentReturnsEmptyWhenNoneConfigured() {
        when(repository.findFirstByOrderByCreatedAtAsc()).thenReturn(Optional.empty());

        Optional<E2bCredentialResponse> current = service.current();

        assertFalse(current.isPresent());
    }

    // ------------------------------------------------------------------
    // isActive - check if credential is active
    // ------------------------------------------------------------------

    @Test
    void isActiveReturnsTrueWhenCredentialIsActive() {
        E2bCredential credential = new E2bCredential("encrypted::key");
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
        E2bCredential credential = new E2bCredential("encrypted::key");
        credential.deactivate();
        when(repository.findFirstByOrderByCreatedAtAsc()).thenReturn(Optional.of(credential));

        assertFalse(service.isActive());
    }

    // ------------------------------------------------------------------
    // deactivate - deactivate credential
    // ------------------------------------------------------------------

    @Test
    void deactivateMarksCredentialAsInactive() {
        E2bCredential credential = new E2bCredential("encrypted::key");
        when(repository.findFirstByOrderByCreatedAtAsc()).thenReturn(Optional.of(credential));
        when(repository.save(credential)).thenReturn(credential);

        service.deactivate();

        assertEquals(E2bCredentialStatus.INACTIVE, credential.getStatus());
        verify(repository, times(1)).save(credential);
    }

    @Test
    void deactivateIsNoOpWhenNothingConfigured() {
        when(repository.findFirstByOrderByCreatedAtAsc()).thenReturn(Optional.empty());

        service.deactivate(); // should not throw

        verify(repository, times(0)).save(any());
    }

    // ------------------------------------------------------------------
    // hasCodeExecutionTemplate - check if template is configured
    // ------------------------------------------------------------------

    @Test
    void hasCodeExecutionTemplateReturnsTrueWhenTemplateIsConfigured() {
        E2bCredential credential = new E2bCredential("encrypted::key");
        credential.updateCodeExecutionTemplateId("template-xyz-123");
        when(repository.findFirstByOrderByCreatedAtAsc()).thenReturn(Optional.of(credential));

        assertTrue(service.hasCodeExecutionTemplate());
    }

    @Test
    void hasCodeExecutionTemplateReturnsFalseWhenNoTemplateConfigured() {
        E2bCredential credential = new E2bCredential("encrypted::key");
        // No template set (defaults to null)
        when(repository.findFirstByOrderByCreatedAtAsc()).thenReturn(Optional.of(credential));

        assertFalse(service.hasCodeExecutionTemplate());
    }

    @Test
    void hasCodeExecutionTemplateReturnsFalseWhenCredentialNotActive() {
        E2bCredential credential = new E2bCredential("encrypted::key");
        credential.updateCodeExecutionTemplateId("template-123");
        credential.deactivate();
        when(repository.findFirstByOrderByCreatedAtAsc()).thenReturn(Optional.of(credential));

        assertFalse(service.hasCodeExecutionTemplate());
    }

    @Test
    void hasCodeExecutionTemplateReturnsFalseWhenNothingConfigured() {
        when(repository.findFirstByOrderByCreatedAtAsc()).thenReturn(Optional.empty());

        assertFalse(service.hasCodeExecutionTemplate());
    }

    // ------------------------------------------------------------------
    // resolveActiveApiKey - resolve plaintext key for internal use
    // ------------------------------------------------------------------

    @Test
    void resolveActiveApiKeyReturnsDecryptedKeyWhenCredentialIsActive() {
        String encryptedKey = "encrypted::key";
        String plainKey = "test-api-key-123";
        E2bCredential credential = new E2bCredential(encryptedKey);
        when(repository.findFirstByOrderByCreatedAtAsc()).thenReturn(Optional.of(credential));
        when(encryptionService.decrypt(encryptedKey)).thenReturn(plainKey);

        String resolved = service.resolveActiveApiKey();

        assertEquals(plainKey, resolved);
        verify(encryptionService, times(1)).decrypt(encryptedKey);
    }

    @Test
    void resolveActiveApiKeyThrowsWhenNoCredentialConfigured() {
        when(repository.findFirstByOrderByCreatedAtAsc()).thenReturn(Optional.empty());

        E2bNotConfiguredException thrown = assertThrows(E2bNotConfiguredException.class, () -> service.resolveActiveApiKey());
        assertTrue(thrown.getMessage().contains("No active E2B credential"));
    }

    @Test
    void resolveActiveApiKeyThrowsWhenCredentialIsInactive() {
        E2bCredential credential = new E2bCredential("encrypted::key");
        credential.deactivate();
        when(repository.findFirstByOrderByCreatedAtAsc()).thenReturn(Optional.of(credential));

        assertThrows(E2bNotConfiguredException.class, () -> service.resolveActiveApiKey());
    }

    // ------------------------------------------------------------------
    // configureCodeExecutionTemplate - set the template ID
    // ------------------------------------------------------------------

    @Test
    void configureCodeExecutionTemplateUpdatesTemplateId() {
        E2bCredential credential = new E2bCredential("encrypted::key");
        when(repository.findFirstByOrderByCreatedAtAsc()).thenReturn(Optional.of(credential));
        when(repository.save(credential)).thenReturn(credential);

        E2bCredentialResponse response = service.configureCodeExecutionTemplate("template-abc-123");

        assertNotNull(response);
        assertEquals("template-abc-123", credential.getCodeExecutionTemplateId());
        verify(repository, times(1)).save(credential);
    }

    @Test
    void configureCodeExecutionTemplateThrowsWhenNoCredentialConfigured() {
        when(repository.findFirstByOrderByCreatedAtAsc()).thenReturn(Optional.empty());

        assertThrows(E2bNotConfiguredException.class, () -> service.configureCodeExecutionTemplate("template-123"));
    }

    @Test
    void configureCodeExecutionTemplateThrowsWhenCredentialIsInactive() {
        E2bCredential credential = new E2bCredential("encrypted::key");
        credential.deactivate();
        when(repository.findFirstByOrderByCreatedAtAsc()).thenReturn(Optional.of(credential));

        assertThrows(E2bNotConfiguredException.class, () -> service.configureCodeExecutionTemplate("template-123"));
    }

    // ------------------------------------------------------------------
    // resolveCodeExecutionTemplateId - resolve template ID for internal use
    // ------------------------------------------------------------------

    @Test
    void resolveCodeExecutionTemplateIdReturnsTemplateWhenConfigured() {
        E2bCredential credential = new E2bCredential("encrypted::key");
        credential.updateCodeExecutionTemplateId("template-xyz-789");
        when(repository.findFirstByOrderByCreatedAtAsc()).thenReturn(Optional.of(credential));

        String templateId = service.resolveCodeExecutionTemplateId();

        assertEquals("template-xyz-789", templateId);
    }

    @Test
    void resolveCodeExecutionTemplateIdThrowsWhenNoCredentialConfigured() {
        when(repository.findFirstByOrderByCreatedAtAsc()).thenReturn(Optional.empty());

        E2bNotConfiguredException thrown = assertThrows(E2bNotConfiguredException.class, () -> service.resolveCodeExecutionTemplateId());
        assertTrue(thrown.getMessage().contains("No active E2B credential"));
    }

    @Test
    void resolveCodeExecutionTemplateIdThrowsWithTemplateMessageWhenTemplateNotSet() {
        E2bCredential credential = new E2bCredential("encrypted::key");
        // No template configured (stays null)
        when(repository.findFirstByOrderByCreatedAtAsc()).thenReturn(Optional.of(credential));

        E2bNotConfiguredException thrown = assertThrows(E2bNotConfiguredException.class, () -> service.resolveCodeExecutionTemplateId());
        assertTrue(thrown.getMessage().contains("code-execution template"));
    }

    @Test
    void resolveCodeExecutionTemplateIdThrowsWhenCredentialIsInactive() {
        E2bCredential credential = new E2bCredential("encrypted::key");
        credential.updateCodeExecutionTemplateId("template-123");
        credential.deactivate();
        when(repository.findFirstByOrderByCreatedAtAsc()).thenReturn(Optional.of(credential));

        assertThrows(E2bNotConfiguredException.class, () -> service.resolveCodeExecutionTemplateId());
    }
}
