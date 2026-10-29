package com.agenticai.core.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.providers.ProviderName;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * The translation-job settings are a singleton: first read creates the documented defaults, and an
 * update only overwrites the fields the caller actually supplied (nulls keep their values - a
 * partial PUT must not blank the rest of the row).
 */
class TranslationJobSettingsServiceTest {

    private TranslationJobSettingsRepository repository;
    private TranslationJobSettingsService service;

    @BeforeEach
    void setUp() {
        repository = mock(TranslationJobSettingsRepository.class);
        service = new TranslationJobSettingsService(repository);
    }

    @Test
    void firstReadCreatesTheDocumentedDefaultRow() {
        when(repository.findById(TranslationJobSettings.SINGLETON_ID)).thenReturn(Optional.empty());
        when(repository.save(any(TranslationJobSettings.class))).thenAnswer(i -> i.getArgument(0));

        TranslationJobSettings settings = service.get();

        ArgumentCaptor<TranslationJobSettings> captor =
                ArgumentCaptor.forClass(TranslationJobSettings.class);
        verify(repository).save(captor.capture());

        TranslationJobSettings saved = captor.getValue();
        assertEquals(TranslationJobSettings.SINGLETON_ID, saved.getId());
        assertTrue(
                saved.getCronScheduleDisplay().contains("0 6 * * 1"),
                "the default mirrors the real weekly cron, not an invented one");
        assertEquals(ProviderName.ANTHROPIC, saved.getProviderName());
        assertEquals("claude-3-5-sonnet-latest", saved.getModel());
        assertEquals(".github/workflows/translate-messages.yml", saved.getWorkflowPathHint());
        assertNull(saved.getNotes());
        assertNotNull(saved.getUpdatedAt());
        assertSame(saved, settings);
    }

    @Test
    void anExistingRowIsReturnedWithoutRewritingIt() {
        TranslationJobSettings existing =
                new TranslationJobSettings(
                        "0 0 * * *", ProviderName.OPENAI, "model-a", "wf.yml", "n");
        when(repository.findById(TranslationJobSettings.SINGLETON_ID))
                .thenReturn(Optional.of(existing));

        assertSame(existing, service.get());
    }

    @Test
    void updateAppliesOnlyTheSuppliedFields() {
        TranslationJobSettings existing =
                new TranslationJobSettings(
                        "0 6 * * 1", ProviderName.ANTHROPIC, "old-model", "wf.yml", "keep me");
        when(repository.findById(TranslationJobSettings.SINGLETON_ID))
                .thenReturn(Optional.of(existing));
        when(repository.save(any(TranslationJobSettings.class))).thenAnswer(i -> i.getArgument(0));

        TranslationJobSettings updated =
                service.update(
                        null, ProviderName.OPENAI, "new-model", null, null, "ops@example.com");

        assertEquals(ProviderName.OPENAI, updated.getProviderName());
        assertEquals("new-model", updated.getModel());
        assertEquals(
                "0 6 * * 1", updated.getCronScheduleDisplay(), "unspecified keeps the old value");
        assertEquals("wf.yml", updated.getWorkflowPathHint());
        assertEquals("keep me", updated.getNotes());
        assertEquals("ops@example.com", updated.getUpdatedBy());
        verify(repository).save(existing);
    }

    @Test
    void entityBehaviourRoundTripsTheDispatchRecord() {
        TranslationJobSettings settings =
                new TranslationJobSettings(
                        "cron", ProviderName.ANTHROPIC, "model", "wf.yml", "notes");

        assertEquals(TranslationJobSettings.SINGLETON_ID, settings.getId());
        assertNull(settings.getLastTriggeredAt());
        assertNull(settings.getLastTriggerStatus());

        settings.recordTrigger("ops@example.com", "DISPATCHED", "run requested");

        assertNotNull(settings.getLastTriggeredAt());
        assertEquals("ops@example.com", settings.getLastTriggeredBy());
        assertEquals("DISPATCHED", settings.getLastTriggerStatus());
        assertEquals("run requested", settings.getLastTriggerMessage());
    }
}
