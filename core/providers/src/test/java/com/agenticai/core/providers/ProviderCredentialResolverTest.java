package com.agenticai.core.providers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.crypto.EncryptionService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * {@link ProviderCredentialResolver} is the one place every real LLM call
 * turns a stored, encrypted credential into a usable plaintext key - the
 * "credential resolution" seam that sits underneath every agent chat turn,
 * the Ask-AI helper, and the AI workflow generator (see its own javadoc: "the
 * decrypted string is never cached or logged by this class"). Had zero test
 * coverage before this suite despite that blast radius - a regression here
 * (e.g. skipping the ACTIVE-status filter, or the SARVAM branch resolving
 * the wrong table) silently breaks every provider at once, or leaks an
 * inactive/rejected key into a live call.
 *
 * <p>{@link LlmProviderCredential} is mocked rather than constructed for the
 * cases needing a specific {@code providerName}/{@code status}/{@code
 * encryptedApiKey} - same rationale {@code HumanApprovalGateServiceTest}
 * documents for {@code ApprovalRequest}: simpler than constructing via the
 * real constructor and separately faking JPA-managed fields.
 */
class ProviderCredentialResolverTest {

    private LlmProviderCredentialRepository repository;
    private EncryptionService encryptionService;
    private ExternalProviderCredentialSource sarvamSource;
    private ProviderCredentialResolver resolver;

    @BeforeEach
    void setUp() {
        repository = mock(LlmProviderCredentialRepository.class);
        encryptionService = mock(EncryptionService.class);
        // ADR-0011 phase 2: the SARVAM branch is now a contributed
        // ExternalProviderCredentialSource (sarvam.SarvamProviderCredentialSource
        // in the full distribution) - a mock stands in for it here.
        sarvamSource = mock(ExternalProviderCredentialSource.class);
        when(sarvamSource.providerName()).thenReturn(ProviderName.SARVAM);
        resolver = new ProviderCredentialResolver(repository, encryptionService, List.of(sarvamSource));
    }

    // --- resolveActiveKey ---------------------------------------------------

    @Test
    void resolveActiveKeyForSarvamBypassesTheLlmProviderCredentialTableEntirely() {
        when(sarvamSource.resolveActiveApiKey()).thenReturn("sarvam-plaintext-key");

        String key = resolver.resolveActiveKey(ProviderName.SARVAM);

        assertEquals("sarvam-plaintext-key", key);
        verify(repository, never()).findByProviderName(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void withoutAContributedSourceSarvamFallsBackToTheLlmProviderCredentialTable() {
        // A distribution without the sarvam module: no special case left in the
        // kernel, SARVAM resolves like any other provider (and reports "not active").
        ProviderCredentialResolver slim = new ProviderCredentialResolver(repository, encryptionService, List.of());
        when(repository.findByProviderName(ProviderName.SARVAM)).thenReturn(Optional.empty());

        assertThrows(ProviderNotActiveException.class, () -> slim.resolveActiveKey(ProviderName.SARVAM));
    }

    @Test
    void twoSourcesForTheSameProviderFailFastAtConstruction() {
        ExternalProviderCredentialSource duplicate = mock(ExternalProviderCredentialSource.class);
        when(duplicate.providerName()).thenReturn(ProviderName.SARVAM);

        assertThrows(IllegalStateException.class,
            () -> new ProviderCredentialResolver(repository, encryptionService, List.of(sarvamSource, duplicate)));
    }

    /** Stand-in for the vendor's own "not configured" exception (SarvamNotConfiguredException). */
    static class VendorNotConfiguredException extends RuntimeException {}

    @Test
    void resolveActiveKeyForSarvamPropagatesNotConfiguredExceptionUnchanged() {
        // No separate exception type is invented for the SARVAM branch - the
        // resolver deliberately lets SarvamNotConfiguredException surface as-is
        // so GlobalExceptionHandler's existing mapping for it applies, same as
        // this method's own javadoc documents.
        when(sarvamSource.resolveActiveApiKey()).thenThrow(new VendorNotConfiguredException());

        assertThrows(VendorNotConfiguredException.class, () -> resolver.resolveActiveKey(ProviderName.SARVAM));
    }

    @Test
    void resolveActiveKeyDecryptsAnActiveCredential() {
        LlmProviderCredential credential = mock(LlmProviderCredential.class);
        when(credential.getStatus()).thenReturn(ProviderStatus.ACTIVE);
        when(credential.getEncryptedApiKey()).thenReturn("cipher-text");
        when(repository.findByProviderName(ProviderName.ANTHROPIC)).thenReturn(Optional.of(credential));
        when(encryptionService.decrypt("cipher-text")).thenReturn("sk-real-key");

        String key = resolver.resolveActiveKey(ProviderName.ANTHROPIC);

        assertEquals("sk-real-key", key);
    }

    @Test
    void resolveActiveKeyRejectsAnInvalidStatusCredentialEvenThoughARowExists() {
        // A row existing is not enough - only status == ACTIVE counts as
        // usable. A rejected/never-validated key must not silently be
        // handed to a real provider call.
        LlmProviderCredential credential = mock(LlmProviderCredential.class);
        when(credential.getStatus()).thenReturn(ProviderStatus.INVALID);
        when(repository.findByProviderName(ProviderName.OPENAI)).thenReturn(Optional.of(credential));

        assertThrows(ProviderNotActiveException.class, () -> resolver.resolveActiveKey(ProviderName.OPENAI));
        verify(encryptionService, never()).decrypt(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void resolveActiveKeyThrowsWhenNoRowExistsAtAll() {
        when(repository.findByProviderName(ProviderName.GEMINI)).thenReturn(Optional.empty());

        assertThrows(ProviderNotActiveException.class, () -> resolver.resolveActiveKey(ProviderName.GEMINI));
    }

    // --- resolveAnyActiveKey ------------------------------------------------

    @Test
    void resolveAnyActiveKeyReturnsTheOldestActivatedCredentialsProviderAndDecryptedKey() {
        LlmProviderCredential credential = mock(LlmProviderCredential.class);
        when(credential.getProviderName()).thenReturn(ProviderName.GROK);
        when(credential.getEncryptedApiKey()).thenReturn("cipher");
        when(repository.findByStatusOrderByCreatedAtAsc(ProviderStatus.ACTIVE)).thenReturn(List.of(credential));
        when(encryptionService.decrypt("cipher")).thenReturn("plain");

        ProviderCredentialResolver.ActiveProvider active = resolver.resolveAnyActiveKey();

        assertEquals(ProviderName.GROK, active.providerName());
        assertEquals("plain", active.apiKey());
    }

    @Test
    void resolveAnyActiveKeyThrowsNoActiveProviderWhenNothingIsActive() {
        when(repository.findByStatusOrderByCreatedAtAsc(ProviderStatus.ACTIVE)).thenReturn(List.of());

        assertThrows(NoActiveProviderException.class, () -> resolver.resolveAnyActiveKey());
    }

    // --- ranking by the tenant's own call history ---------------------------

    private static LlmProviderCredential active(ProviderName name, String cipher) {
        LlmProviderCredential credential = mock(LlmProviderCredential.class);
        when(credential.getProviderName()).thenReturn(name);
        when(credential.getEncryptedApiKey()).thenReturn(cipher);
        return credential;
    }

    /** Credentials are built BEFORE the stubbing call, so their own stubs never nest inside it. */
    private void activeCredentials(LlmProviderCredential... credentials) {
        when(repository.findByStatusOrderByCreatedAtAsc(ProviderStatus.ACTIVE)).thenReturn(List.of(credentials));
    }

    private ProviderCredentialResolver resolverWith(Map<ProviderName, ProviderUsageSignal.Usage> usage) {
        @SuppressWarnings("unchecked")
        ObjectProvider<ProviderUsageSignal> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(providers -> usage);
        return new ProviderCredentialResolver(repository, encryptionService, List.of(sarvamSource), provider);
    }

    /**
     * Prod 2026-10-05: the workspace's oldest ACTIVE key (Vertex) was being
     * refused by the provider while its OpenRouter key answered every call;
     * the judge, Ask-AI and the generators all went to Vertex and failed.
     */
    @Test
    void resolveAnyActiveKeySkipsAnOlderCredentialTheProviderIsRejectingForOneThatWorks() {
        Instant now = Instant.parse("2026-10-05T10:00:00Z");
        activeCredentials(
            active(ProviderName.GOOGLE_VERTEX, "vertex-cipher"), active(ProviderName.OPENROUTER, "or-cipher"));
        when(encryptionService.decrypt("or-cipher")).thenReturn("or-plain");
        ProviderCredentialResolver ranked = resolverWith(Map.of(
            ProviderName.GOOGLE_VERTEX, new ProviderUsageSignal.Usage(null, null, now, true),
            ProviderName.OPENROUTER,
            new ProviderUsageSignal.Usage(now.minusSeconds(60), "deepseek/deepseek-chat", null, false)));

        ProviderCredentialResolver.ActiveProvider picked = ranked.resolveAnyActiveKey();

        assertEquals(ProviderName.OPENROUTER, picked.providerName());
        assertEquals("or-plain", picked.apiKey());
        List<ProviderCredentialResolver.RankedProvider> order = ranked.rankActiveProviders();
        assertEquals(ProviderName.GOOGLE_VERTEX, order.get(1).providerName());
        assertTrue(order.get(1).credentialSuspect());
        assertEquals("deepseek/deepseek-chat", order.get(0).lastWorkingModel());
    }

    @Test
    void rankingPrefersProvenThenUntriedThenFailingAndKeepsActivationOrderOnTies() {
        Instant now = Instant.parse("2026-10-05T10:00:00Z");
        activeCredentials(
            active(ProviderName.GROK, "a"),       // rejected credential
            active(ProviderName.MISTRAL, "b"),    // never called
            active(ProviderName.GROQ, "c"),       // never called, activated after MISTRAL
            active(ProviderName.OPENAI, "d"),     // worked, then a rate limit
            active(ProviderName.ANTHROPIC, "e"),  // newest call worked
            active(ProviderName.DEEPSEEK, "f")); // newest call worked more recently, activated later
        ProviderCredentialResolver ranked = resolverWith(Map.of(
            ProviderName.GROK, new ProviderUsageSignal.Usage(now.minusSeconds(900), "grok", now, true),
            ProviderName.OPENAI, new ProviderUsageSignal.Usage(now.minusSeconds(300), "gpt", now, false),
            ProviderName.ANTHROPIC, new ProviderUsageSignal.Usage(now.minusSeconds(200), "claude", null, false),
            ProviderName.DEEPSEEK,
            new ProviderUsageSignal.Usage(now.minusSeconds(100), "ds", now.minusSeconds(500), true)));

        List<ProviderName> order = ranked.rankActiveProviders().stream()
            .map(ProviderCredentialResolver.RankedProvider::providerName).toList();

        assertEquals(List.of(ProviderName.ANTHROPIC, ProviderName.DEEPSEEK, ProviderName.OPENAI,
            ProviderName.MISTRAL, ProviderName.GROQ, ProviderName.GROK), order);
    }

    @Test
    void aSoleRejectedCredentialIsStillResolvedSoTheCallFailsWithTheProvidersOwnError() {
        activeCredentials(
            active(ProviderName.GOOGLE_VERTEX, "vertex-cipher"));
        when(encryptionService.decrypt("vertex-cipher")).thenReturn("vertex-plain");
        ProviderCredentialResolver ranked = resolverWith(Map.of(ProviderName.GOOGLE_VERTEX,
            new ProviderUsageSignal.Usage(null, null, Instant.parse("2026-10-05T10:00:00Z"), true)));

        assertEquals(ProviderName.GOOGLE_VERTEX, ranked.resolveAnyActiveKey().providerName());
    }

    @Test
    void aFailingUsageSignalNeverBreaksCredentialResolution() {
        activeCredentials(
            active(ProviderName.GROK, "a"), active(ProviderName.OPENAI, "b"));
        when(encryptionService.decrypt("a")).thenReturn("plain");
        @SuppressWarnings("unchecked")
        ObjectProvider<ProviderUsageSignal> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(providers -> {
            throw new IllegalStateException("call log unavailable");
        });
        ProviderCredentialResolver ranked =
            new ProviderCredentialResolver(repository, encryptionService, List.of(sarvamSource), provider);

        assertEquals(ProviderName.GROK, ranked.resolveAnyActiveKey().providerName());
    }
}
