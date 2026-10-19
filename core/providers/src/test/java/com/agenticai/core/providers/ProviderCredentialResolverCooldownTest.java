package com.agenticai.core.providers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.crypto.EncryptionService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Prod 2026-10-05 (R1): an hourly sweep kept calling a Vertex key the provider had refused - 24 failed calls a day
 * for two weeks - in a workspace whose OpenRouter key worked. Background callers now skip a refused key for a
 * cooldown and, when nothing else is connected, do not call at all.
 */
class ProviderCredentialResolverCooldownTest {

    private LlmProviderCredentialRepository repository;
    private EncryptionService encryptionService;

    @BeforeEach
    void setUp() {
        repository = mock(LlmProviderCredentialRepository.class);
        encryptionService = mock(EncryptionService.class);
    }

    private static LlmProviderCredential active(ProviderName name, String cipher) {
        LlmProviderCredential credential = mock(LlmProviderCredential.class);
        when(credential.getProviderName()).thenReturn(name);
        when(credential.getEncryptedApiKey()).thenReturn(cipher);
        return credential;
    }

    private void activeCredentials(LlmProviderCredential... credentials) {
        when(repository.findByStatusOrderByCreatedAtAsc(ProviderStatus.ACTIVE)).thenReturn(List.of(credentials));
    }

    private ProviderCredentialResolver resolverWith(Map<ProviderName, ProviderUsageSignal.Usage> usage) {
        @SuppressWarnings("unchecked")
        ObjectProvider<ProviderUsageSignal> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(providers -> usage);
        return new ProviderCredentialResolver(repository, encryptionService, List.of(), provider);
    }

    @Test
    void aBackgroundCallDoesNotCallAProviderThatRefusedTheKeyWithinTheCooldown() {
        activeCredentials(active(ProviderName.GOOGLE_VERTEX, "vertex-cipher"));
        ProviderCredentialResolver resolver = resolverWith(Map.of(ProviderName.GOOGLE_VERTEX,
            new ProviderUsageSignal.Usage(null, null, Instant.now().minusSeconds(3600), true)));

        ProviderCoolingDownException e = assertThrows(ProviderCoolingDownException.class, () -> resolver.resolveBest(true));

        assertEquals(ProviderName.GOOGLE_VERTEX, e.provider());
        assertTrue(e.retryAfter().isAfter(Instant.now().plusSeconds(4 * 3600)), e.retryAfter().toString());
        assertTrue(e.getMessage().contains("rejected"), e.getMessage());
        verify(encryptionService, never()).decrypt(anyString());
    }

    @Test
    void thePersonDrivenPathStillGetsTheRefusedKeySoTheProvidersOwnErrorReachesThem() {
        activeCredentials(active(ProviderName.GOOGLE_VERTEX, "vertex-cipher"));
        when(encryptionService.decrypt("vertex-cipher")).thenReturn("vertex-plain");
        ProviderCredentialResolver resolver = resolverWith(Map.of(ProviderName.GOOGLE_VERTEX,
            new ProviderUsageSignal.Usage(null, null, Instant.now().minusSeconds(60), true)));

        ProviderCredentialResolver.ResolvedProvider picked = resolver.resolveBest(false);

        assertEquals(ProviderName.GOOGLE_VERTEX, picked.providerName());
        assertTrue(picked.credentialSuspect());
    }

    @Test
    void aWorkspaceWithAWorkingAlternativeGetsThatOneInTheBackgroundToo() {
        Instant now = Instant.now();
        activeCredentials(
            active(ProviderName.GOOGLE_VERTEX, "vertex-cipher"), active(ProviderName.OPENROUTER, "or-cipher"));
        when(encryptionService.decrypt("or-cipher")).thenReturn("or-plain");
        ProviderCredentialResolver resolver = resolverWith(Map.of(
            ProviderName.GOOGLE_VERTEX, new ProviderUsageSignal.Usage(null, null, now.minusSeconds(60), true),
            ProviderName.OPENROUTER,
            new ProviderUsageSignal.Usage(now.minusSeconds(30), "deepseek/deepseek-chat", null, false)));

        ProviderCredentialResolver.ResolvedProvider picked = resolver.resolveBest(true);

        assertEquals(ProviderName.OPENROUTER, picked.providerName());
        assertEquals("or-plain", picked.apiKey());
        assertEquals("deepseek/deepseek-chat", picked.lastWorkingModel());
        verify(encryptionService, never()).decrypt("vertex-cipher");
    }

    @Test
    void afterTheCooldownOneProbeCallIsLetThroughToFindOutWhetherTheKeyWasFixed() {
        activeCredentials(active(ProviderName.GOOGLE_VERTEX, "vertex-cipher"));
        when(encryptionService.decrypt("vertex-cipher")).thenReturn("vertex-plain");
        ProviderCredentialResolver resolver = resolverWith(Map.of(ProviderName.GOOGLE_VERTEX,
            new ProviderUsageSignal.Usage(null, null, Instant.now().minusSeconds(7 * 3600), true)));

        assertEquals(ProviderName.GOOGLE_VERTEX, resolver.resolveBest(true).providerName());
    }

    @Test
    void theCooldownIsConfigurable() {
        activeCredentials(active(ProviderName.GOOGLE_VERTEX, "vertex-cipher"));
        ProviderCredentialResolver resolver = resolverWith(Map.of(ProviderName.GOOGLE_VERTEX,
            new ProviderUsageSignal.Usage(null, null, Instant.now().minusSeconds(7 * 3600), true)));
        resolver.rejectedCooldownHours = 24;

        assertThrows(ProviderCoolingDownException.class, () -> resolver.resolveBest(true));
    }

    @Test
    void aFailureThatIsNotAboutTheCredentialNeverPausesTheProvider() {
        activeCredentials(active(ProviderName.GROQ, "groq-cipher"));
        when(encryptionService.decrypt("groq-cipher")).thenReturn("groq-plain");
        ProviderCredentialResolver resolver = resolverWith(Map.of(ProviderName.GROQ,
            new ProviderUsageSignal.Usage(null, null, Instant.now().minusSeconds(10), false)));

        assertEquals(ProviderName.GROQ, resolver.resolveBest(true).providerName());
    }

    @Test
    void aKeyThatAnsweredSinceItWasRefusedIsNotPaused() {
        Instant now = Instant.now();
        activeCredentials(active(ProviderName.GOOGLE_VERTEX, "vertex-cipher"));
        when(encryptionService.decrypt("vertex-cipher")).thenReturn("vertex-plain");
        ProviderCredentialResolver resolver = resolverWith(Map.of(ProviderName.GOOGLE_VERTEX,
            new ProviderUsageSignal.Usage(now.minusSeconds(10), "gemini-2.5-flash", now.minusSeconds(600), true)));

        assertEquals(ProviderName.GOOGLE_VERTEX, resolver.resolveBest(true).providerName());
    }

    @Test
    void backgroundResolutionWithNothingActiveIsStillNoActiveProvider() {
        when(repository.findByStatusOrderByCreatedAtAsc(ProviderStatus.ACTIVE)).thenReturn(List.of());

        assertThrows(NoActiveProviderException.class, () -> resolverWith(Map.of()).resolveBest(true));
    }
}
