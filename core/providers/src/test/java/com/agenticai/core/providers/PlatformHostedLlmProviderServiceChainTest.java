package com.agenticai.core.providers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.agenticai.core.crypto.EncryptionService;
import com.agenticai.core.providers.PlatformHostedLlmProviderService.HostedProviderSelection;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;

/**
 * P3 (PLATFORM-ADMIN-GOVERNANCE-AND-CONTROLS.md §6 + Appendix A) - the pool's resolution side of
 * the fallback chain. Where the pre-P3 pool exposed only {@code resolveTopPriorityEnabled} (one row
 * - an OpenRouter free-tier 429 then just failed the turn), {@link
 * PlatformHostedLlmProviderService#resolveEnabledChain} now returns EVERY enabled row in priority
 * order so {@code AgentChatService} can fall through them. These tests mock the repository to
 * return a multi-row chain and pin: the chain preserves the repository's priority-ascending order
 * and decrypts every row's key; the single-pick view is still exactly the chain head; and the
 * master switch / empty-pool emptiness contract is unchanged.
 */
class PlatformHostedLlmProviderServiceChainTest {

    private PlatformHostedLlmProviderRepository repository;
    private HostedLlmPoolSettingsRepository poolSettingsRepository;
    private EncryptionService encryptionService;
    private PlatformHostedLlmProviderService service;

    @BeforeEach
    void setUp() {
        repository = mock(PlatformHostedLlmProviderRepository.class);
        poolSettingsRepository = mock(HostedLlmPoolSettingsRepository.class);
        encryptionService = mock(EncryptionService.class);

        RestClient.Builder restClientBuilder = mock(RestClient.Builder.class);
        when(restClientBuilder.build()).thenReturn(mock(RestClient.class));

        service =
                new PlatformHostedLlmProviderService(
                        repository,
                        poolSettingsRepository,
                        encryptionService,
                        restClientBuilder,
                        mock(ProviderModelCatalogService.class));
    }

    private void poolEnabled(boolean enabled) {
        when(poolSettingsRepository.findById(HostedLlmPoolSettings.SINGLETON_ID))
                .thenReturn(Optional.of(new HostedLlmPoolSettings(enabled)));
    }

    private PlatformHostedLlmProvider provider(String label, String encryptedKey, int priority) {
        return new PlatformHostedLlmProvider(
                label,
                "https://api." + label + ".test",
                "model-" + label,
                encryptedKey,
                true,
                priority,
                null,
                null);
    }

    @Test
    void resolveEnabledChainReturnsEveryEnabledRowInPriorityOrderWithDecryptedKeys() {
        poolEnabled(true);
        // Documented recommended chain: Groq (primary) -> Gemini -> Cerebras.
        PlatformHostedLlmProvider groq = provider("groq", "enc-groq", 0);
        PlatformHostedLlmProvider gemini = provider("gemini", "enc-gemini", 10);
        PlatformHostedLlmProvider cerebras = provider("cerebras", "enc-cerebras", 20);
        when(repository.findAllByEnabledTrueOrderByPriorityAsc())
                .thenReturn(List.of(groq, gemini, cerebras));
        when(encryptionService.decrypt("enc-groq")).thenReturn("key-groq");
        when(encryptionService.decrypt("enc-gemini")).thenReturn("key-gemini");
        when(encryptionService.decrypt("enc-cerebras")).thenReturn("key-cerebras");

        List<HostedProviderSelection> chain = service.resolveEnabledChain();

        assertEquals(3, chain.size());
        assertSame(groq, chain.get(0).provider());
        assertSame(gemini, chain.get(1).provider());
        assertSame(cerebras, chain.get(2).provider());
        assertEquals("key-groq", chain.get(0).decryptedApiKey());
        assertEquals("key-gemini", chain.get(1).decryptedApiKey());
        assertEquals("key-cerebras", chain.get(2).decryptedApiKey());
    }

    @Test
    void resolveTopPriorityEnabledIsExactlyTheChainHead() {
        poolEnabled(true);
        PlatformHostedLlmProvider groq = provider("groq", "enc-groq", 0);
        PlatformHostedLlmProvider gemini = provider("gemini", "enc-gemini", 10);
        when(repository.findAllByEnabledTrueOrderByPriorityAsc()).thenReturn(List.of(groq, gemini));
        when(encryptionService.decrypt("enc-groq")).thenReturn("key-groq");
        when(encryptionService.decrypt("enc-gemini")).thenReturn("key-gemini");

        Optional<HostedProviderSelection> head = service.resolveTopPriorityEnabled();

        assertTrue(head.isPresent());
        assertSame(groq, head.get().provider());
        assertEquals("key-groq", head.get().decryptedApiKey());
    }

    @Test
    void aRowWhoseKeyCannotBeDecryptedIsSkippedNotFatalForTheWholeChain() {
        poolEnabled(true);
        PlatformHostedLlmProvider broken = provider("broken", "unused-mock-key", 0);
        PlatformHostedLlmProvider gemini = provider("gemini", "enc-gemini", 10);
        when(repository.findAllByEnabledTrueOrderByPriorityAsc())
                .thenReturn(List.of(broken, gemini));
        when(encryptionService.decrypt("unused-mock-key"))
                .thenThrow(new IllegalStateException("Failed to decrypt secret"));
        when(encryptionService.decrypt("enc-gemini")).thenReturn("key-gemini");

        List<HostedProviderSelection> chain = service.resolveEnabledChain();

        assertEquals(1, chain.size());
        assertSame(gemini, chain.get(0).provider());
        assertEquals("key-gemini", chain.get(0).decryptedApiKey());
        // The single-pick view is the chain head, so it falls through to the next usable row too.
        assertSame(gemini, service.resolveTopPriorityEnabled().orElseThrow().provider());
    }

    @Test
    void anUndecryptableRowIsReportedOnceNotOnEveryTurn() {
        poolEnabled(true);
        PlatformHostedLlmProvider broken = provider("broken", "unused-mock-key", 0);
        when(repository.findAllByEnabledTrueOrderByPriorityAsc()).thenReturn(List.of(broken));
        when(encryptionService.decrypt("unused-mock-key"))
                .thenThrow(new IllegalStateException("Failed to decrypt secret"));
        Logger logger = (Logger) LoggerFactory.getLogger(PlatformHostedLlmProviderService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            for (int turn = 0; turn < 5; turn++) {
                assertTrue(service.resolveEnabledChain().isEmpty());
            }
        } finally {
            logger.detachAppender(appender);
        }

        long warnings = appender.list.stream().filter(e -> e.getLevel() == Level.WARN).count();
        assertEquals(1, warnings, "one WARN for the row, not one per turn");
    }

    @Test
    void ifEveryKeyIsUndecryptableThePoolIsEmptyNotAnError() {
        poolEnabled(true);
        PlatformHostedLlmProvider broken = provider("broken", "unused-mock-key", 0);
        when(repository.findAllByEnabledTrueOrderByPriorityAsc()).thenReturn(List.of(broken));
        when(encryptionService.decrypt("unused-mock-key"))
                .thenThrow(new IllegalStateException("Failed to decrypt secret"));

        assertTrue(service.resolveEnabledChain().isEmpty());
    }

    @Test
    void masterSwitchOffYieldsEmptyChainAndNeverTouchesTheRepositoryOrDecrypt() {
        poolEnabled(false);

        assertTrue(service.resolveEnabledChain().isEmpty());
        assertTrue(service.resolveTopPriorityEnabled().isEmpty());
        // The master switch short-circuits before any row is read or decrypted.
        verifyNoInteractions(encryptionService);
    }

    @Test
    void enabledPoolWithNoRowsYieldsEmptyChain() {
        poolEnabled(true);
        when(repository.findAllByEnabledTrueOrderByPriorityAsc()).thenReturn(List.of());

        assertTrue(service.resolveEnabledChain().isEmpty());
        assertFalse(service.resolveTopPriorityEnabled().isPresent());
        // Two reads: one for the direct resolveEnabledChain() call, one for the
        // resolveTopPriorityEnabled() call that now delegates to the chain -
        // pinning that the single-pick view is a thin wrapper over the chain.
        verify(repository, times(2)).findAllByEnabledTrueOrderByPriorityAsc();
    }
}
