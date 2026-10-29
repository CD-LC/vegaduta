package com.agenticai.core.omnichannel.email;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;

/** {@link EmailSuppressionService}: idempotent, normalized writes to the suppression list. */
class EmailSuppressionServiceTest {

    private EmailSuppressionRepository repository;
    private EmailSuppressionService service;

    @BeforeEach
    void setUp() {
        repository = mock(EmailSuppressionRepository.class);
        service = new EmailSuppressionService(repository);
    }

    @Test
    void suppressIgnoresBlankInput() {
        service.suppress(null, "MANUAL");
        service.suppress("   ", "MANUAL");
        verify(repository, never()).existsByEmail(anyString());
        verify(repository, never()).save(any());
    }

    @Test
    void suppressWritesANewEntryNormalizedToLowerCase() {
        when(repository.existsByEmail("lead@x.test")).thenReturn(false);

        service.suppress("  Lead@X.Test ", "BOUNCE");

        ArgumentCaptor<EmailSuppression> saved = ArgumentCaptor.forClass(EmailSuppression.class);
        verify(repository).save(saved.capture());
        assertEquals("lead@x.test", saved.getValue().getEmail());
        assertEquals("BOUNCE", saved.getValue().getReason());
    }

    @Test
    void suppressIsANoOpForAnAlreadySuppressedAddress() {
        when(repository.existsByEmail("lead@x.test")).thenReturn(true);

        service.suppress("lead@x.test", "MANUAL");

        verify(repository, never()).save(any());
    }

    @Test
    void suppressSwallowsARacingInsert() {
        when(repository.existsByEmail("lead@x.test")).thenReturn(false);
        when(repository.save(any(EmailSuppression.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate key"));

        // Must not throw: the unique constraint means the goal is already met.
        service.suppress("lead@x.test", "UNSUBSCRIBE");
        verify(repository).save(any(EmailSuppression.class));
    }
}
