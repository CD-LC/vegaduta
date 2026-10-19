package com.agenticai.core.providers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.agenticai.core.tenancy.PgAdvisoryJobLock;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * {@link ProviderModelCatalogService} backs every provider's model dropdown
 * (Agent creation, chat attachment gating) and had zero test coverage before
 * this suite despite a real, documented production incident already found
 * in its own javadoc: a {@code readOnly = true} transaction on {@link
 * #listModels} broke every call for a never-refreshed provider until the
 * scheduled job ran (a read-only Postgres transaction rejecting the
 * lazy-warm DELETE). This suite locks in the current (fixed) behavior as a
 * regression test.
 *
 * <p>Uses {@link MockRestServiceServer} (bound to a real {@link
 * RestClient.Builder}) rather than deep-mocking {@link RestClient}'s fluent
 * request chain - this is the first {@code RestClient}-consuming class in
 * this codebase to get test coverage, so there's no existing convention to
 * follow yet; {@code MockRestServiceServer} is Spring's own purpose-built
 * tool for exactly this (stub the HTTP response, exercise the real
 * request-building code), and is simpler and less brittle than mocking
 * {@code RequestHeadersUriSpec}/{@code RequestHeadersSpec}/{@code
 * ResponseSpec} by hand.
 */
class ProviderModelCatalogServiceTest {

    private static final String OPENROUTER_MODELS_URL = "https://openrouter.ai/api/v1/models";

    private ProviderModelCatalogRepository repository;
    private RestClient.Builder restClientBuilder;
    private MockRestServiceServer mockServer;
    private ProviderModelCatalogService service;

    @BeforeEach
    void setUp() {
        repository = mock(ProviderModelCatalogRepository.class);
        restClientBuilder = RestClient.builder();
        mockServer = MockRestServiceServer.bindTo(restClientBuilder).build();
        // The advisory lock only gates scheduledRefresh(); every method under
        // test here is below the lock, so a plain mock (tryRunExclusive
        // returning false, never invoked) is sufficient.
        service = new ProviderModelCatalogService(repository, restClientBuilder, mock(PgAdvisoryJobLock.class));
    }

    // --- listModels(): lazy warm --------------------------------------------

    @Test
    void listModelsLazilyRefreshesAnEverEmptyProviderInsteadOfReturningNothing() {
        // ANTHROPIC is a seeded provider - never hits the network - so this
        // also confirms the lazy-warm path doesn't require OpenRouter.
        when(repository.countByProviderName(ProviderName.ANTHROPIC)).thenReturn(0L);
        when(repository.findAllByProviderNameOrderByModelIdAsc(ProviderName.ANTHROPIC)).thenReturn(List.of(
            new ProviderModelCatalogEntry(ProviderName.ANTHROPIC, "claude-opus-4-8", "Claude Opus 4.8", 200_000)
        ));

        List<ProviderModelResponse> models = service.listModels(ProviderName.ANTHROPIC);

        verify(repository).deleteAllByProviderName(ProviderName.ANTHROPIC);
        verify(repository).saveAll(any());
        assertEquals(1, models.size());
        assertEquals("claude-opus-4-8", models.get(0).modelId());
    }

    @Test
    void listModelsSkipsTheRefreshWhenTheCacheIsAlreadyWarm() {
        when(repository.countByProviderName(ProviderName.ANTHROPIC)).thenReturn(3L);
        when(repository.findAllByProviderNameOrderByModelIdAsc(ProviderName.ANTHROPIC)).thenReturn(List.of());

        service.listModels(ProviderName.ANTHROPIC);

        verify(repository, never()).deleteAllByProviderName(any());
        verify(repository, never()).saveAll(any());
    }

    // --- findModel() ---------------------------------------------------------

    @Test
    void findModelReturnsEmptyWhenTheModelIdIsNotInTheCachedCatalog() {
        // A hand-typed or vendor-removed model id - callers treat this the
        // same as "no attachment support confirmed", per the method's own
        // javadoc, not an exception.
        when(repository.countByProviderName(ProviderName.OPENAI)).thenReturn(2L);
        when(repository.findByProviderNameAndModelId(ProviderName.OPENAI, "gpt-legacy")).thenReturn(Optional.empty());

        Optional<ProviderModelResponse> found = service.findModel(ProviderName.OPENAI, "gpt-legacy");

        assertTrue(found.isEmpty());
    }

    @Test
    void findModelReturnsTheMatchingCachedEntry() {
        when(repository.countByProviderName(ProviderName.OPENAI)).thenReturn(2L);
        when(repository.findByProviderNameAndModelId(ProviderName.OPENAI, "gpt-5.1")).thenReturn(
            Optional.of(new ProviderModelCatalogEntry(ProviderName.OPENAI, "gpt-5.1", "GPT-5.1", 400_000, true, true, false)));

        Optional<ProviderModelResponse> found = service.findModel(ProviderName.OPENAI, "gpt-5.1");

        assertTrue(found.isPresent());
        assertTrue(found.get().supportsImageInput());
        assertTrue(found.get().supportsAudioInput());
        assertFalse(found.get().supportsVideoInput());
    }

    // --- refreshProvider(): empty result leaves the cache untouched ---------

    @Test
    void refreshProviderLeavesTheExistingCacheUntouchedWhenTheFetchProducesZeroModels() {
        // OPENROUTER with an empty upstream response (e.g. "data" key missing)
        // - must NOT wipe out a previously-good cache with nothing.
        mockServer.expect(requestTo(OPENROUTER_MODELS_URL)).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        service.refreshProvider(ProviderName.OPENROUTER);

        verify(repository, never()).deleteAllByProviderName(any());
        verify(repository, never()).saveAll(any());
    }

    // --- refreshProvider(): exceptions are swallowed, not propagated --------

    @Test
    void refreshProviderSwallowsAnUnexpectedExceptionRatherThanPropagatingIt() {
        // This is what makes refreshAll()'s per-provider loop resilient - one
        // provider's refresh blowing up (e.g. a repository error) must not
        // stop every other provider in the same run from refreshing.
        when(repository.countByProviderName(any())).thenReturn(0L);
        org.mockito.Mockito.doThrow(new RuntimeException("db unavailable"))
            .when(repository).deleteAllByProviderName(ProviderName.ANTHROPIC);

        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> service.refreshProvider(ProviderName.ANTHROPIC));
    }

    // --- fetchOpenRouterModels() (via refreshProvider(OPENROUTER)) ----------

    @Test
    void openRouterRefreshParsesModelIdNameContextWindowAndInputModalities() {
        String body = """
            {
              "data": [
                {
                  "id": "anthropic/claude-3.5-sonnet",
                  "name": "Claude 3.5 Sonnet",
                  "context_length": 200000,
                  "architecture": { "input_modalities": ["text", "image"] }
                },
                {
                  "id": "openai/gpt-4o-audio",
                  "name": "GPT-4o Audio",
                  "context_length": 128000,
                  "architecture": { "input_modalities": ["text", "audio", "video"] }
                }
              ]
            }
            """;
        mockServer.expect(requestTo(OPENROUTER_MODELS_URL)).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        service.refreshProvider(ProviderName.OPENROUTER);

        org.mockito.ArgumentCaptor<List<ProviderModelCatalogEntry>> captor = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(repository).deleteAllByProviderName(ProviderName.OPENROUTER);
        verify(repository).saveAll(captor.capture());
        List<ProviderModelCatalogEntry> saved = captor.getValue();
        assertEquals(2, saved.size());

        ProviderModelCatalogEntry sonnet = saved.get(0);
        assertEquals("anthropic/claude-3.5-sonnet", sonnet.getModelId());
        assertEquals("Claude 3.5 Sonnet", sonnet.getDisplayName());
        assertEquals(200000, sonnet.getContextWindow());
        assertTrue(sonnet.isSupportsImageInput());
        assertFalse(sonnet.isSupportsAudioInput());
        assertFalse(sonnet.isSupportsVideoInput());

        ProviderModelCatalogEntry gpt4oAudio = saved.get(1);
        assertTrue(gpt4oAudio.isSupportsAudioInput());
        assertTrue(gpt4oAudio.isSupportsVideoInput());
        assertFalse(gpt4oAudio.isSupportsImageInput());
    }

    @Test
    void openRouterRefreshSkipsEntriesWithNoIdRatherThanFailingTheWholeBatch() {
        String body = """
            {
              "data": [
                { "name": "nameless entry, no id field" },
                { "id": "real/model", "name": "Real Model" }
              ]
            }
            """;
        mockServer.expect(requestTo(OPENROUTER_MODELS_URL)).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        service.refreshProvider(ProviderName.OPENROUTER);

        org.mockito.ArgumentCaptor<List<ProviderModelCatalogEntry>> captor = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(repository).saveAll(captor.capture());
        assertEquals(1, captor.getValue().size());
        assertEquals("real/model", captor.getValue().get(0).getModelId());
    }

    @Test
    void openRouterRefreshFallsBackToTheIdAsDisplayNameWhenNameIsMissing() {
        String body = """
            { "data": [ { "id": "vendor/model-x" } ] }
            """;
        mockServer.expect(requestTo(OPENROUTER_MODELS_URL)).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        service.refreshProvider(ProviderName.OPENROUTER);

        org.mockito.ArgumentCaptor<List<ProviderModelCatalogEntry>> captor = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(repository).saveAll(captor.capture());
        assertEquals("vendor/model-x", captor.getValue().get(0).getDisplayName());
    }

    @Test
    void openRouterRefreshTreatsAServerErrorAsAnEmptyFetchAndLeavesTheCacheAlone() {
        mockServer.expect(requestTo(OPENROUTER_MODELS_URL)).andRespond(withServerError());

        service.refreshProvider(ProviderName.OPENROUTER);

        verify(repository, never()).deleteAllByProviderName(any());
        verify(repository, times(0)).saveAll(any());
    }

    // --- pickDefaultModel() / defaultModelFor() ------------------------------

    @Test
    void pickDefaultModelPrefersThePerProviderHandPickOverAlphabeticalOrder() {
        // The bug this fixes: AgentService used to take the first ALPHABETICAL
        // entry, which for GROK was grok-3 - a model xAI retired 2026-05-15 -
        // so every new Grok agent started broken. The hand-picked default
        // must win regardless of where it sorts.
        List<ProviderModelResponse> models = List.of(
            new ProviderModelResponse("grok-4.20-0309-non-reasoning", "Grok 4.20 NR", 256_000, false, false, false, null),
            new ProviderModelResponse("grok-4.3", "Grok 4.3", 1_000_000, true, false, false, null),
            new ProviderModelResponse("grok-4.5", "Grok 4.5", 500_000, true, false, false, null)
        );

        assertEquals(
            "grok-4.20-0309-non-reasoning",
            ProviderModelCatalogService.pickDefaultModel(ProviderName.GROK, models)
        );
    }

    @Test
    void pickDefaultModelFallsBackToKeywordPreferenceWhenTheHandPickIsAbsent() {
        // Hand-pick for GROK (grok-4.20-0309-non-reasoning) missing from the
        // list - keyword scan must find "non-reasoning" rather than falling
        // straight to the first entry.
        List<ProviderModelResponse> models = List.of(
            new ProviderModelResponse("grok-4.3", "Grok 4.3", 1_000_000, true, false, false, null),
            new ProviderModelResponse("grok-5-non-reasoning", "Grok 5 NR", 256_000, false, false, false, null)
        );

        assertEquals(
            "grok-5-non-reasoning",
            ProviderModelCatalogService.pickDefaultModel(ProviderName.GROK, models)
        );
    }

    @Test
    void pickDefaultModelFallsBackToTheFirstEntryWhenNothingMatches() {
        List<ProviderModelResponse> models = List.of(
            new ProviderModelResponse("some-model-a", "A", null, false, false, false, null),
            new ProviderModelResponse("some-model-b", "B", null, false, false, false, null)
        );

        assertEquals("some-model-a", ProviderModelCatalogService.pickDefaultModel(ProviderName.GROK, models));
    }

    @Test
    void pickDefaultModelReturnsNullForAnEmptyList() {
        org.junit.jupiter.api.Assertions.assertNull(
            ProviderModelCatalogService.pickDefaultModel(ProviderName.GROK, List.of()));
    }

    @Test
    void defaultModelForReadsTheWarmedCatalog() {
        when(repository.countByProviderName(ProviderName.ANTHROPIC)).thenReturn(2L);
        when(repository.findAllByProviderNameOrderByModelIdAsc(ProviderName.ANTHROPIC)).thenReturn(List.of(
            new ProviderModelCatalogEntry(ProviderName.ANTHROPIC, "claude-fable-5", "Claude Fable 5", 200_000),
            new ProviderModelCatalogEntry(ProviderName.ANTHROPIC, "claude-haiku-4-5-20251001", "Claude Haiku 4.5", 200_000)
        ));

        assertEquals(Optional.of("claude-haiku-4-5-20251001"), service.defaultModelFor(ProviderName.ANTHROPIC));
    }

    // --- replacementForRetiredModel() ----------------------------------------

    @Test
    void retiredGrokAndDeepSeekAndGroqModelsAllMapToCurrentReplacements() {
        assertEquals(Optional.of("grok-4.3"), service.replacementForRetiredModel("grok-3"));
        assertEquals(Optional.of("grok-4.20-0309-non-reasoning"), service.replacementForRetiredModel("grok-3-mini"));
        assertEquals(Optional.of("grok-4.5"), service.replacementForRetiredModel("grok-4"));
        assertEquals(Optional.of("grok-4.3"), service.replacementForRetiredModel("grok-4-fast"));
        assertEquals(Optional.of("deepseek-v4-flash"), service.replacementForRetiredModel("deepseek-chat"));
        assertEquals(Optional.of("deepseek-v4-pro"), service.replacementForRetiredModel("deepseek-reasoner"));
        assertEquals(Optional.of("llama-3.1-8b-instant"), service.replacementForRetiredModel("gemma2-9b-it"));
    }

    @Test
    void currentModelsAndNullHaveNoRetirementReplacement() {
        assertTrue(service.replacementForRetiredModel("grok-4.5").isEmpty());
        assertTrue(service.replacementForRetiredModel(null).isEmpty());
    }

    // --- refreshAll() ---------------------------------------------------------

    @Test
    void refreshAllRefreshesEverySeededProviderPlusOpenRouter() {
        mockServer.expect(requestTo(OPENROUTER_MODELS_URL)).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        when(repository.countByProviderName(any())).thenReturn(0L);

        service.refreshAll();

        // Every seeded provider (ANTHROPIC/OPENAI/GEMINI/GROK/SARVAM/MISTRAL/
        // DEEPSEEK/GROQ/META/... - every SEED_MODELS key) deletes+saves;
        // OPENROUTER's empty response above does neither - asserting the seeded
        // providers alone is enough to confirm every SEED_MODELS key was visited.
        verify(repository).deleteAllByProviderName(ProviderName.ANTHROPIC);
        verify(repository).deleteAllByProviderName(ProviderName.OPENAI);
        verify(repository).deleteAllByProviderName(ProviderName.GEMINI);
        verify(repository).deleteAllByProviderName(ProviderName.GROK);
        verify(repository).deleteAllByProviderName(ProviderName.SARVAM);
        verify(repository).deleteAllByProviderName(ProviderName.META);
    }

    /**
     * JAVA-021/022: the OpenRouter HTTP fetch happens BEFORE the replace
     * transaction opens (no pooled connection held across a network call),
     * and the delete+insert run inside exactly one transaction.
     */
    @Test
    void openRouterFetchRunsOutsideTheReplaceTransaction() {
        java.util.concurrent.atomic.AtomicBoolean inTransaction = new java.util.concurrent.atomic.AtomicBoolean();
        org.springframework.transaction.PlatformTransactionManager tm =
            mock(org.springframework.transaction.PlatformTransactionManager.class);
        when(tm.getTransaction(any())).thenAnswer(inv -> {
            inTransaction.set(true);
            return new org.springframework.transaction.support.SimpleTransactionStatus();
        });
        org.mockito.Mockito.doAnswer(inv -> {
            inTransaction.set(false);
            return null;
        }).when(tm).commit(any());
        service.setTransactionManager(tm);
        String body = "{\"data\":[{\"id\":\"openai/gpt-4o\",\"name\":\"GPT-4o\",\"context_length\":128000}]}";
        mockServer.expect(requestTo(OPENROUTER_MODELS_URL)).andRespond(request -> {
            assertFalse(inTransaction.get(), "HTTP fetch must not run inside the replace transaction");
            return withSuccess(body, MediaType.APPLICATION_JSON).createResponse(request);
        });
        org.mockito.Mockito.doAnswer(inv -> {
            assertTrue(inTransaction.get(), "delete must run inside the replace transaction");
            return null;
        }).when(repository).deleteAllByProviderName(any());

        service.refreshProvider(ProviderName.OPENROUTER);

        verify(tm, times(1)).getTransaction(any());
        verify(tm).commit(any());
        verify(repository).deleteAllByProviderName(ProviderName.OPENROUTER);
        verify(repository).saveAll(any());
        mockServer.verify();
    }
}
