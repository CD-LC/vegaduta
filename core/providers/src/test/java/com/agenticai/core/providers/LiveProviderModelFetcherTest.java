package com.agenticai.core.providers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.agenticai.core.providers.LiveProviderModelFetcher.LiveModel;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * {@link LiveProviderModelFetcher} sits directly on the tenant-facing model
 * dropdown request path, so its two contracts matter more than its parsing:
 * it must NEVER throw (any failure degrades to the cached catalog upstream),
 * and it must not leak non-chat models (embeddings/speech/image rows every
 * flat vendor list includes) into a chat model picker. Same {@link
 * MockRestServiceServer}-over-a-real-builder style as {@link
 * ProviderModelCatalogServiceTest} - see that suite's javadoc for why this
 * beats deep-mocking RestClient's fluent chain.
 */
class LiveProviderModelFetcherTest {

    private MockRestServiceServer mockServer;
    private LiveProviderModelFetcher fetcher;

    @BeforeEach
    void setUp() {
        RestClient.Builder restClientBuilder = RestClient.builder();
        mockServer = MockRestServiceServer.bindTo(restClientBuilder).build();
        // The RestClient(-not-Builder) test seam: the public constructor's
        // timeout requestFactory would replace the mock factory bindTo just
        // installed - see the fetcher's package-private constructor javadoc.
        fetcher = new LiveProviderModelFetcher(restClientBuilder.build());
    }

    // --- GROK: rich language-models endpoint, ids-only fallback -------------

    @Test
    void grokPrefersTheRichLanguageModelsEndpointAndReadsModalities() {
        String body = """
            {
              "models": [
                {
                  "id": "grok-4.5",
                  "input_modalities": ["text", "image"],
                  "output_modalities": ["text"],
                  "context_window": 500000
                },
                {
                  "id": "grok-4.20-0309-non-reasoning",
                  "input_modalities": ["text"],
                  "context_window": 256000
                }
              ]
            }
            """;
        mockServer.expect(requestTo(LiveProviderModelFetcher.GROK_LANGUAGE_MODELS_URL))
            .andExpect(header("Authorization", "Bearer xai-key"))
            .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        List<LiveModel> models = fetcher.fetch(ProviderName.GROK, "xai-key");

        assertEquals(2, models.size());
        assertEquals("grok-4.5", models.get(0).modelId());
        assertTrue(models.get(0).supportsImageInput());
        assertEquals(500_000, models.get(0).contextWindow());
        assertFalse(models.get(1).supportsImageInput());
    }

    @Test
    void grokFallsBackToTheConventionalModelsEndpointWhenLanguageModelsFails() {
        mockServer.expect(requestTo(LiveProviderModelFetcher.GROK_LANGUAGE_MODELS_URL))
            .andRespond(withServerError());
        mockServer.expect(requestTo(LiveProviderModelFetcher.GROK_MODELS_URL))
            .andRespond(withSuccess("""
                { "data": [ { "id": "grok-4.3" } ] }
                """, MediaType.APPLICATION_JSON));

        List<LiveModel> models = fetcher.fetch(ProviderName.GROK, "xai-key");

        assertEquals(1, models.size());
        assertEquals("grok-4.3", models.get(0).modelId());
    }

    // --- GEMINI: generateContent filter + models/ prefix strip ---------------

    @Test
    void geminiStripsTheModelsPrefixAndFiltersToGenerateContentModels() {
        String body = """
            {
              "models": [
                {
                  "name": "models/gemini-2.5-flash",
                  "displayName": "Gemini 2.5 Flash",
                  "description": "Fast multimodal model",
                  "inputTokenLimit": 1000000,
                  "supportedGenerationMethods": ["generateContent", "countTokens"]
                },
                {
                  "name": "models/text-bison-legacy",
                  "supportedGenerationMethods": ["generateText"]
                }
              ]
            }
            """;
        mockServer.expect(requestTo(LiveProviderModelFetcher.GEMINI_MODELS_URL))
            .andExpect(header("x-goog-api-key", "gemini-key"))
            .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        List<LiveModel> models = fetcher.fetch(ProviderName.GEMINI, "gemini-key");

        assertEquals(1, models.size());
        assertEquals("gemini-2.5-flash", models.get(0).modelId());
        assertEquals("Gemini 2.5 Flash", models.get(0).displayName());
        assertEquals("Fast multimodal model", models.get(0).description());
        assertEquals(1_000_000, models.get(0).contextWindow());
    }

    // --- GROQ: active=false rows dropped -------------------------------------

    @Test
    void groqDropsInactiveRowsAndReadsContextWindow() {
        String body = """
            {
              "data": [
                { "id": "llama-3.1-8b-instant", "context_window": 128000, "active": true },
                { "id": "decommissioned-model", "active": false }
              ]
            }
            """;
        mockServer.expect(requestTo(LiveProviderModelFetcher.GROQ_MODELS_URL))
            .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        List<LiveModel> models = fetcher.fetch(ProviderName.GROQ, "groq-key");

        assertEquals(1, models.size());
        assertEquals("llama-3.1-8b-instant", models.get(0).modelId());
        assertEquals(128_000, models.get(0).contextWindow());
    }

    // --- ANTHROPIC: versioned header, display_name ---------------------------

    @Test
    void anthropicSendsTheVersionHeaderAndReadsDisplayNames() {
        String body = """
            { "data": [ { "id": "claude-fable-5", "display_name": "Claude Fable 5" } ] }
            """;
        mockServer.expect(requestTo(LiveProviderModelFetcher.ANTHROPIC_MODELS_URL))
            .andExpect(header("x-api-key", "sk-ant-key"))
            .andExpect(header("anthropic-version", LiveProviderModelFetcher.ANTHROPIC_VERSION))
            .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        List<LiveModel> models = fetcher.fetch(ProviderName.ANTHROPIC, "sk-ant-key");

        assertEquals(1, models.size());
        assertEquals("Claude Fable 5", models.get(0).displayName());
    }

    // --- META: plain OpenAI-compatible ids list, non-chat siblings dropped ---

    @Test
    void metaUsesTheOpenAiCompatibleIdsListAndDropsItsImageAndTranscribeModels() {
        // dev.meta.ai (2026-09-17): /v1/models lists the Muse Spark chat line
        // next to muse-image-1.0 (image gen) and muse-voice-transcribe-1.0
        // (STT). Only the chat ids may reach the dropdown.
        String body = """
            {
              "data": [
                { "id": "muse-spark-1.3" },
                { "id": "muse-spark-1.3-contributor" },
                { "id": "muse-image-1.0" },
                { "id": "muse-voice-transcribe-1.0" }
              ]
            }
            """;
        mockServer.expect(requestTo(LiveProviderModelFetcher.META_MODELS_URL))
            .andExpect(header("Authorization", "Bearer LLM|123|abc"))
            .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        List<LiveModel> models = fetcher.fetch(ProviderName.META, "LLM|123|abc");

        assertEquals(List.of("muse-spark-1.3", "muse-spark-1.3-contributor"),
            models.stream().map(LiveModel::modelId).toList());
    }

    // --- non-chat denylist ----------------------------------------------------

    @Test
    void nonChatModelsAreFilteredOutOfEveryLiveList() {
        String body = """
            {
              "data": [
                { "id": "gpt-4o-mini" },
                { "id": "text-embedding-004" },
                { "id": "whisper-large-v3" },
                { "id": "gpt-4o-mini-tts" },
                { "id": "dall-e-3" }
              ]
            }
            """;
        mockServer.expect(requestTo(LiveProviderModelFetcher.OPENAI_MODELS_URL))
            .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        List<LiveModel> models = fetcher.fetch(ProviderName.OPENAI, "sk-key");

        assertEquals(1, models.size());
        assertEquals("gpt-4o-mini", models.get(0).modelId());
    }

    // --- failure contract: empty, never an exception --------------------------

    @Test
    void anUpstreamErrorProducesAnEmptyListNotAnException() {
        mockServer.expect(requestTo(LiveProviderModelFetcher.MISTRAL_MODELS_URL))
            .andRespond(withServerError());

        assertTrue(fetcher.fetch(ProviderName.MISTRAL, "mistral-key").isEmpty());
    }

    @Test
    void providersWithNoLiveEndpointReturnEmptyWithoutAnyNetworkCall() {
        // No mockServer expectation - a network call would fail this test.
        assertTrue(fetcher.fetch(ProviderName.SARVAM, "sarvam-key").isEmpty());
        assertTrue(fetcher.fetch(ProviderName.OLLAMA, "unused").isEmpty());
        assertTrue(fetcher.fetch(ProviderName.PLATFORM_HOSTED, "unused").isEmpty());
    }

    @Test
    void openRouterNeedsNoKeyAndCarriesDescriptionsAndModalities() {
        String body = """
            {
              "data": [
                {
                  "id": "anthropic/claude-sonnet-5",
                  "name": "Claude Sonnet 5",
                  "description": "Balanced model",
                  "context_length": 200000,
                  "architecture": { "input_modalities": ["text", "image"] }
                }
              ]
            }
            """;
        mockServer.expect(requestTo(LiveProviderModelFetcher.OPENROUTER_MODELS_URL))
            .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        List<LiveModel> models = fetcher.fetch(ProviderName.OPENROUTER, null);

        assertEquals(1, models.size());
        assertEquals("Balanced model", models.get(0).description());
        assertTrue(models.get(0).supportsImageInput());
        assertEquals(200_000, models.get(0).contextWindow());
    }
}
