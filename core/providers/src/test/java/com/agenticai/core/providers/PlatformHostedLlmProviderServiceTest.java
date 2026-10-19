package com.agenticai.core.providers;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.agenticai.core.crypto.EncryptionService;
import com.agenticai.core.providers.validation.ProviderValidationException;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * Focuses on the one behaviour that is genuinely new/tricky in this service:
 * how {@code validateKey}'s {@code {baseUrl}/v1/models} probe copes with a
 * provider whose OpenAI-compat endpoint is NOT {@code
 * {baseUrl}/v1/chat/completions}. For such a row (the motivating case being
 * Gemini) the fixed {@code /v1/models} probe would hit the wrong URL, so the
 * probe is instead derived from the custom {@code completionsPath} (or skipped
 * entirely when it can't be derived) - a valid Gemini row must never fail to
 * save on account of a wrong metadata probe.
 */
class PlatformHostedLlmProviderServiceTest {

    private PlatformHostedLlmProviderRepository repository;
    private HostedLlmPoolSettingsRepository poolSettingsRepository;
    private EncryptionService encryptionService;
    private MockRestServiceServer mockServer;
    private PlatformHostedLlmProviderService service;

    private ProviderModelCatalogService providerModelCatalogService;

    @BeforeEach
    void setUp() {
        repository = mock(PlatformHostedLlmProviderRepository.class);
        poolSettingsRepository = mock(HostedLlmPoolSettingsRepository.class);
        encryptionService = mock(EncryptionService.class);
        providerModelCatalogService = mock(ProviderModelCatalogService.class);
        RestClient.Builder builder = RestClient.builder();
        mockServer = MockRestServiceServer.bindTo(builder).build();
        service = new PlatformHostedLlmProviderService(
            repository, poolSettingsRepository, encryptionService, builder, providerModelCatalogService
        );
        // Offline, deterministic DNS: every host NAME resolves to a public address;
        // IP literals still parse locally and are classified as-is (SEC-023).
        service.setSsrfGuard(com.agenticai.core.security.net.SsrfGuard.of(java.util.Set.of(), host ->
            host.chars().allMatch(c -> Character.isDigit(c) || c == '.') || host.contains(":")
                ? java.net.InetAddress.getAllByName(host)
                : new java.net.InetAddress[] {java.net.InetAddress.getByName("93.184.216.34")}));
        when(encryptionService.encrypt(anyString())).thenReturn("ciphertext");
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    // --- static URL-shaping helpers ---------------------------------------

    @Test
    void deriveModelsUrlSwapsChatCompletionsForModels() {
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/openai/models",
            HostedPoolEndpointProbe.deriveModelsUrl(
                "https://generativelanguage.googleapis.com", "/v1beta/openai/chat/completions"
            )
        );
    }

    @Test
    void deriveModelsUrlReturnsNullWhenPathIsNotAChatCompletionsPath() {
        // Signals "no probe possible - stay tolerant" rather than fabricating a URL.
        assertNull(HostedPoolEndpointProbe.deriveModelsUrl("https://x.example", "/weird/endpoint"));
    }

    @Test
    void effectiveBaseUrlStripsV1OnlyForConventionalRows() {
        // Conventional row (null path): trailing /v1 stripped, as before.
        assertEquals(
            "https://api.together.xyz",
            HostedPoolEndpointProbe.effectiveBaseUrl("https://api.together.xyz/v1", null)
        );
        // Custom-path row: base URL left verbatim, even a genuine trailing /v1.
        assertEquals(
            "https://example.com/api/v1",
            HostedPoolEndpointProbe.effectiveBaseUrl("https://example.com/api/v1", "/openai/chat/completions")
        );
    }

    // --- validateKey tolerance through create() ---------------------------

    @Test
    void geminiRowValidatesAgainstDerivedModelsUrlAndSaves() {
        mockServer.expect(requestTo("https://generativelanguage.googleapis.com/v1beta/openai/models"))
            .andExpect(method(HttpMethod.GET))
            .andExpect(header("Authorization", "Bearer AIza-real-key"))
            .andRespond(withSuccess());

        PlatformHostedLlmProviderUpsertRequest request = new PlatformHostedLlmProviderUpsertRequest(
            "Gemini", "https://generativelanguage.googleapis.com", "gemini-2.0-flash-lite",
            "AIza-real-key", true, 100, null, "/v1beta/openai/chat/completions", null
        );

        PlatformHostedLlmProviderResponse response = assertDoesNotThrow(() -> service.create(request));

        assertEquals("https://generativelanguage.googleapis.com", response.baseUrl());
        assertEquals("/v1beta/openai/chat/completions", response.completionsPath());
        mockServer.verify();
    }

    @Test
    void conventionalRowStillProbesV1Models() {
        mockServer.expect(requestTo("https://api.groq.com/openai/v1/models"))
            .andExpect(method(HttpMethod.GET))
            .andRespond(withSuccess());

        PlatformHostedLlmProviderUpsertRequest request = new PlatformHostedLlmProviderUpsertRequest(
            "Groq", "https://api.groq.com/openai", "llama-3.1-8b-instant", "gsk-key", true, 100, null, null, null
        );

        PlatformHostedLlmProviderResponse response = assertDoesNotThrow(() -> service.create(request));

        assertNull(response.completionsPath());
        mockServer.verify();
    }

    // --- defensive trimming + required fields -----------------------------

    @Test
    void upsertRequestTrimsEveryFreeTextFieldAndCollapsesBlankToNull() {
        PlatformHostedLlmProviderUpsertRequest request = new PlatformHostedLlmProviderUpsertRequest(
            "  Gemini ", " https://generativelanguage.googleapis.com ", " gemini-2.0-flash-lite",
            "AIza-real-key\n", true, 100, "   ", " /v1beta/openai/chat/completions ", null
        );

        assertEquals("Gemini", request.label());
        assertEquals("https://generativelanguage.googleapis.com", request.baseUrl());
        // The production failure this guards: ONE leading space on the model id
        // is sent verbatim in the request body and comes back as 404 NOT_FOUND,
        // while the key-validation probe (which never looks at the model) still
        // saves the row happily.
        assertEquals("gemini-2.0-flash-lite", request.model());
        // Same hazard for the key: validateKey already trimmed for its probe,
        // so an untrimmed key validated and then 401'd on every real call.
        assertEquals("AIza-real-key", request.apiKey());
        assertNull(request.region());
        assertEquals("/v1beta/openai/chat/completions", request.completionsPath());
    }

    @Test
    void blankModelIsRejectedAsAValidationErrorNotADatabaseConstraintViolation() {
        PlatformHostedLlmProviderUpsertRequest request = new PlatformHostedLlmProviderUpsertRequest(
            "Groq", "https://api.groq.com/openai", "   ", "gsk-key", true, 100, null, null, null
        );

        // No mockServer expectation is set: the check must fire before any
        // upstream probe, and reaching the DB would be a 500 instead of a 400.
        assertThrows(ProviderValidationException.class, () -> service.create(request));
        mockServer.verify();
    }

    @Test
    void customPathThatIsNotAChatCompletionsPathSkipsTheProbeAndStillSaves() {
        // No mockServer expectation is set: if any HTTP call were attempted the
        // strict MockRestServiceServer would fail. Proves the probe is skipped.
        PlatformHostedLlmProviderUpsertRequest request = new PlatformHostedLlmProviderUpsertRequest(
            "Odd", "https://odd.example", "m", "key", true, 100, null, "/some/nonstandard/generate", null
        );

        assertDoesNotThrow(() -> service.create(request));
        mockServer.verify();
    }

    // --- generic providerName + blank baseUrl/model ("like tenant BYOK") --

    @Test
    void providerNameAutoFillsKnownEndpointAndAutoSelectsBestCatalogModel() {
        when(providerModelCatalogService.listModels(ProviderName.GEMINI)).thenReturn(List.of(
            new ProviderModelResponse("gemini-2.5-pro", "Gemini 2.5 Pro", 1_000_000, true, true, true, null),
            new ProviderModelResponse("gemini-3.1-flash-lite", "Gemini 3.1 Flash-Lite", 1_000_000, true, true, true, null)
        ));
        mockServer.expect(requestTo("https://generativelanguage.googleapis.com/v1beta/openai/models"))
            .andExpect(method(HttpMethod.GET))
            .andExpect(header("Authorization", "Bearer real-key"))
            .andRespond(withSuccess());

        PlatformHostedLlmProviderUpsertRequest request = new PlatformHostedLlmProviderUpsertRequest(
            "Gemini (auto)", null, null, "real-key", true, 100, null, null, ProviderName.GEMINI
        );

        PlatformHostedLlmProviderResponse response = assertDoesNotThrow(() -> service.create(request));

        assertEquals("https://generativelanguage.googleapis.com", response.baseUrl());
        assertEquals("/v1beta/openai/chat/completions", response.completionsPath());
        // "flash-lite" beats "flash" beats the plain-alphabetical catalog order -
        // this pool primarily serves FREE-tier traffic, where cheap/fast wins.
        assertEquals("gemini-3.1-flash-lite", response.model());
        assertEquals("GEMINI", response.providerName());
        mockServer.verify();
    }

    @Test
    void explicitBaseUrlAndModelAlwaysWinOverProviderNameDefaults() {
        mockServer.expect(requestTo("https://custom.example.com/v1/models"))
            .andExpect(method(HttpMethod.GET))
            .andRespond(withSuccess());

        PlatformHostedLlmProviderUpsertRequest request = new PlatformHostedLlmProviderUpsertRequest(
            "Custom OpenAI-compat", "https://custom.example.com", "my-explicit-model",
            "real-key", true, 100, null, null, ProviderName.OPENAI
        );

        PlatformHostedLlmProviderResponse response = assertDoesNotThrow(() -> service.create(request));

        assertEquals("https://custom.example.com", response.baseUrl());
        assertEquals("my-explicit-model", response.model());
        assertEquals(false, response.modelAutoSelected());
        mockServer.verify();
    }

    @Test
    void providerNameNotCompatibleWithHostedPoolIsRejectedBeforeAnyNetworkCall() {
        // No mockServer expectation set: ANTHROPIC isn't OpenAI-chat-completions-
        // compatible, so this must fail before any upstream probe is attempted.
        PlatformHostedLlmProviderUpsertRequest request = new PlatformHostedLlmProviderUpsertRequest(
            "Claude", null, null, "real-key", true, 100, null, null, ProviderName.ANTHROPIC
        );

        assertThrows(ProviderValidationException.class, () -> service.create(request));
        mockServer.verify();
    }

    @Test
    void metaProviderNameAutoFillsApiMetaAiAndProbesItsV1Models() {
        // META joined HOSTED_POOL_COMPATIBLE_PROVIDERS 2026-09-17: a blank
        // baseUrl must derive https://api.meta.ai (no /v1 - the conventional
        // "/v1/models" probe and "/v1/chat/completions" path are appended),
        // and the key must go out as a plain bearer token.
        when(providerModelCatalogService.listModels(ProviderName.META)).thenReturn(List.of(
            new ProviderModelResponse("muse-spark-1.3", "Muse Spark 1.3", 1_048_576, true, true, true, null)
        ));
        mockServer.expect(requestTo("https://api.meta.ai/v1/models"))
            .andExpect(method(HttpMethod.GET))
            .andExpect(header("Authorization", "Bearer LLM|123|abc"))
            .andRespond(withSuccess());

        PlatformHostedLlmProviderUpsertRequest request = new PlatformHostedLlmProviderUpsertRequest(
            "Meta (auto)", null, null, "LLM|123|abc", true, 100, null, null, ProviderName.META
        );

        PlatformHostedLlmProviderResponse response = assertDoesNotThrow(() -> service.create(request));

        assertEquals("https://api.meta.ai", response.baseUrl());
        assertNull(response.completionsPath());
        assertEquals("muse-spark-1.3", response.model());
        assertEquals("META", response.providerName());
        mockServer.verify();
    }

    @Test
    void autoSelectionFailsClearlyWhenCatalogIsEmpty() {
        when(providerModelCatalogService.listModels(ProviderName.GROK)).thenReturn(List.of());

        PlatformHostedLlmProviderUpsertRequest request = new PlatformHostedLlmProviderUpsertRequest(
            "Grok (auto)", null, null, "real-key", true, 100, null, null, ProviderName.GROK
        );

        // No mockServer expectation: must fail before the key-validation probe.
        assertThrows(ProviderValidationException.class, () -> service.create(request));
        mockServer.verify();
    }

    @Test
    void pickBestModelPrefersFastCheapKeywordsOverAlphabeticalOrder() {
        List<ProviderModelResponse> models = List.of(
            new ProviderModelResponse("gpt-5.1", "GPT-5.1", 400_000, true, true, false, null),
            new ProviderModelResponse("gpt-5.1-mini", "GPT-5.1 Mini", 400_000, true, true, false, null),
            new ProviderModelResponse("o4-mini", "o4-mini", 200_000, true, false, false, null)
        );
        assertEquals("gpt-5.1-mini", HostedPoolRequestResolver.pickBestModel(models));
    }

    @Test
    void pickBestModelFallsBackToFirstEntryWhenNoKeywordMatches() {
        List<ProviderModelResponse> models = List.of(
            new ProviderModelResponse("claude-opus-4-8", "Claude Opus 4.8", 200_000, true, false, false, null),
            new ProviderModelResponse("claude-sonnet-5", "Claude Sonnet 5", 200_000, true, false, false, null)
        );
        assertEquals("claude-opus-4-8", HostedPoolRequestResolver.pickBestModel(models));
    }

    @Test
    void pickBestModelReturnsNullForEmptyCatalog() {
        assertNull(HostedPoolRequestResolver.pickBestModel(List.of()));
    }

    /**
     * Meta's full seed (five rows, alphabetical) has no fast/cheap keyword
     * match; the provider-aware overload must land on the catalog's documented
     * default (muse-spark-1.3), never the alphabetical first (1.1) and never a
     * "-contributor" row.
     */
    @Test
    void pickBestModelUsesTheCatalogDefaultWhenNoKeywordMatchesForMeta() {
        List<ProviderModelResponse> models = List.of(
            new ProviderModelResponse("muse-spark-1.1", "Muse Spark 1.1", 1_048_576, true, true, true, null),
            new ProviderModelResponse("muse-spark-1.2", "Muse Spark 1.2", 1_048_576, true, true, true, null),
            new ProviderModelResponse("muse-spark-1.2-contributor", "Muse Spark 1.2 Contributor", 1_048_576, true, true, true, null),
            new ProviderModelResponse("muse-spark-1.3", "Muse Spark 1.3", 1_048_576, true, true, true, null),
            new ProviderModelResponse("muse-spark-1.3-contributor", "Muse Spark 1.3 Contributor", 1_048_576, true, true, true, null)
        );
        assertEquals("muse-spark-1.3", HostedPoolRequestResolver.pickBestModel(ProviderName.META, models));
        // The legacy list-only overload keeps its alphabetical-first contract.
        assertEquals("muse-spark-1.1", HostedPoolRequestResolver.pickBestModel(models));
    }

    // --- discoverModels() live probe (admin form dropdown) ----------------

    @Test
    void discoverModelsReturnsIdsFromOpenAiCompatModelsResponse() {
        mockServer.expect(requestTo("https://api.groq.com/openai/v1/models"))
            .andExpect(method(HttpMethod.GET))
            .andExpect(header("Authorization", "Bearer gsk-key"))
            .andRespond(withSuccess(
                "{\"data\":[{\"id\":\"llama-3.1-8b-instant\"},{\"id\":\"llama-3.3-70b-versatile\"}]}",
                org.springframework.http.MediaType.APPLICATION_JSON
            ));

        List<String> models = service.discoverModels("https://api.groq.com/openai", "gsk-key", null);

        assertEquals(List.of("llama-3.1-8b-instant", "llama-3.3-70b-versatile"), models);
        mockServer.verify();
    }

    @Test
    void discoverModelsRefusesAnInternalBaseUrlWithoutSendingTheKey() {
        // SEC-023: no mockServer expectation - any request would fail verify().
        assertEquals(List.of(), service.discoverModels("http://keycloak:8080", "gsk-key", null));
        assertEquals(List.of(), service.discoverModels("http://169.254.169.254/latest", "gsk-key", null));
        mockServer.verify();
    }

    @Test
    void discoverModelsReturnsEmptyListRatherThanThrowingOnFailure() {
        mockServer.expect(requestTo("https://api.groq.com/openai/v1/models"))
            .andExpect(method(HttpMethod.GET))
            .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withServerError());

        List<String> models = assertDoesNotThrow(
            () -> service.discoverModels("https://api.groq.com/openai", "gsk-key", null)
        );

        assertEquals(List.of(), models);
    }

    @Test
    void everyHostedPoolCompatibleProviderHasAKnownEndpoint() {
        // resolveRequest() does `KNOWN_PROVIDER_ENDPOINTS.get(providerName).baseUrl()`
        // with no null check, so a ProviderName that joins
        // HOSTED_POOL_COMPATIBLE_PROVIDERS without a matching endpoint entry
        // turns a blank-baseUrl upsert into a NullPointerException instead of
        // the ProviderValidationException the admin UI knows how to render.
        // The two collections are written far apart in the file and there is
        // nothing in the type system tying them together - this test is the tie.
        //
        // Discovered 2026-09-17 while evaluating OLLAMA for the pool: adding it
        // to the compatible set was the obvious move and would have introduced
        // exactly this NPE, because a self-hosted runtime has no canonical
        // public endpoint to put in the map. OLLAMA is deliberately NOT in the
        // set for that reason - self-hosted deployments go through the custom
        // baseUrl + model path instead (see the test below).
        for (ProviderName provider : PlatformHostedLlmProviderService.HOSTED_POOL_COMPATIBLE_PROVIDERS) {
            assertTrue(
                HostedPoolKnownEndpoints.KNOWN_PROVIDER_ENDPOINTS.containsKey(provider),
                provider + " is in HOSTED_POOL_COMPATIBLE_PROVIDERS but has no KNOWN_PROVIDER_ENDPOINTS entry - "
                    + "a blank-baseUrl upsert for it would NPE in resolveRequest(). Either add an endpoint or "
                    + "leave it out of the compatible set and use the custom baseUrl path.");
        }
    }

    @Test
    void selfHostedOpenAiCompatibleEndpointIsAcceptedWithNullProviderName() {
        // The path a self-hosted Ollama (or vLLM/llama.cpp) box takes into the
        // pool: no providerName, explicit baseUrl + model. resolveRequest's own
        // rejection message already points here ("Enter a custom OpenAI-compatible
        // base URL and model instead"), so no new ProviderName plumbing is needed
        // to host free-tier inference ourselves.
        //
        // 203.0.113.x is RFC 5737 TEST-NET-3: a public address, so it passes
        // HostedLlmBaseUrlGuard, without pinning a real host into a unit test.
        // (A private/10.x address would NOT pass - which is precisely why this
        // has to run somewhere with a public IP rather than on Container Apps
        // internal ingress.)
        mockServer.expect(requestTo("http://203.0.113.10:11434/v1/models"))
            .andExpect(method(HttpMethod.GET))
            .andRespond(withSuccess());

        PlatformHostedLlmProviderUpsertRequest request = new PlatformHostedLlmProviderUpsertRequest(
            "Self-hosted Ollama (free tier)", "http://203.0.113.10:11434",
            "qwen2.5:1.5b-instruct-q4_K_M", "unused-ollama-accepts-any-key", true, 100, null, null, null
        );

        PlatformHostedLlmProviderResponse response = assertDoesNotThrow(() -> service.create(request));

        assertEquals("http://203.0.113.10:11434", response.baseUrl());
        assertEquals("qwen2.5:1.5b-instruct-q4_K_M", response.model());
        assertNull(response.providerName());
        mockServer.verify();
    }

    // --- V484 zero-cost rows -------------------------------------------------

    @Test
    void allEnabledRowsZeroCostIsTrueOnlyForANonEmptyPoolOfZeroCostRows() {
        PlatformHostedLlmProvider local = mock(PlatformHostedLlmProvider.class);
        when(local.isZeroCost()).thenReturn(true);
        PlatformHostedLlmProvider paid = mock(PlatformHostedLlmProvider.class);
        when(paid.isZeroCost()).thenReturn(false);

        when(repository.findAllByEnabledTrueOrderByPriorityAsc()).thenReturn(List.of());
        assertEquals(false, service.allEnabledRowsZeroCost(), "an empty pool is not unmetered");

        when(repository.findAllByEnabledTrueOrderByPriorityAsc()).thenReturn(List.of(local));
        assertTrue(service.allEnabledRowsZeroCost());

        when(repository.findAllByEnabledTrueOrderByPriorityAsc()).thenReturn(List.of(local, paid));
        assertEquals(false, service.allEnabledRowsZeroCost(), "one paid row makes the pool metered");
    }

    @Test
    void upsertRequestDerivesZeroCostFromTheOllamaProviderUnlessSetExplicitly() {
        PlatformHostedLlmProviderUpsertRequest ollama = new PlatformHostedLlmProviderUpsertRequest(
            "Local", "http://ollama:11434", "qwen3.5:4b", "ollama", true, 1, null, null, ProviderName.OLLAMA);
        assertTrue(ollama.effectiveZeroCost());

        PlatformHostedLlmProviderUpsertRequest groq = new PlatformHostedLlmProviderUpsertRequest(
            "Groq", null, "llama-3.3-70b", "key", true, 1, null, null, ProviderName.GROQ);
        assertEquals(false, groq.effectiveZeroCost());

        PlatformHostedLlmProviderUpsertRequest vllm = new PlatformHostedLlmProviderUpsertRequest(
            "vLLM on the LAN", "http://vllm:8000", "qwen3.5:9b", "x", true, 1, null, null, null, null, null, null,
            Boolean.TRUE);
        assertTrue(vllm.effectiveZeroCost(), "an explicit choice wins over the provider heuristic");
    }
}
