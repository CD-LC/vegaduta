package com.agenticai.core.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import tools.jackson.databind.ObjectMapper;

/**
 * {@link ProviderErrorTranslator} is the seam that decides what a tenant actually sees when an LLM
 * provider call fails - its own javadoc documents a real production bug (#678: a genuine OpenRouter
 * 400 with a useful body message silently collapsed to a generic 502) that this suite locks in the
 * fix for. Had zero test coverage before this suite despite being pure, dependency-free (besides a
 * plain {@link ObjectMapper}), and already proven bug-prone in production.
 */
class ProviderErrorTranslatorTest {

    // null ErrorExplanationService = the explainer absent/disabled - every
    // fallback keeps the static classify() message, which is exactly the
    // pre-Smart-AI behaviour this suite locks in. The explainer's own wiring
    // (budget gate, cache, spend metering) is covered by
    // ErrorExplanationServiceTest plus the dedicated tests at the bottom here.
    private final ProviderErrorTranslator translator =
            new ProviderErrorTranslator(com.agenticai.core.json.JsonMappers.plain(), null);

    // --- WebClientResponseException branch (OpenRouter/Grok - #678) --------

    @Test
    void webClientResponseExceptionWithErrorMessageBodyIsExtracted() {
        WebClientResponseException wcre =
                WebClientResponseException.create(
                        400,
                        "Bad Request",
                        HttpHeaders.EMPTY,
                        "{\"error\":{\"message\":\"Invalid model id: gpt-nonexistent\"}}"
                                .getBytes(StandardCharsets.UTF_8),
                        StandardCharsets.UTF_8);

        UpstreamProviderException result = translator.translate(wcre);

        assertEquals(400, result.getUpstreamStatus());
        assertTrue(
                result.getMessage().contains("Invalid model id: gpt-nonexistent"),
                "the real provider-supplied reason must survive translation, not just a generic status");
        assertTrue(result.getMessage().contains("HTTP 400"));
    }

    @Test
    void webClientResponseExceptionWithTopLevelMessageKeyIsExtracted() {
        // Some providers use a flat {"message": "..."} shape instead of the
        // nested {"error": {"message": "..."}} shape - both are handled.
        WebClientResponseException wcre =
                WebClientResponseException.create(
                        403,
                        "Forbidden",
                        HttpHeaders.EMPTY,
                        "{\"message\":\"Insufficient credits\"}".getBytes(StandardCharsets.UTF_8),
                        StandardCharsets.UTF_8);

        UpstreamProviderException result = translator.translate(wcre);

        assertEquals(403, result.getUpstreamStatus());
        assertTrue(result.getMessage().contains("Insufficient credits"));
    }

    @Test
    void webClientResponseExceptionWithUnparseableBodyFallsBackToRawBody() {
        WebClientResponseException wcre =
                WebClientResponseException.create(
                        502,
                        "Bad Gateway",
                        HttpHeaders.EMPTY,
                        "not json at all".getBytes(StandardCharsets.UTF_8),
                        StandardCharsets.UTF_8);

        UpstreamProviderException result = translator.translate(wcre);

        assertEquals(502, result.getUpstreamStatus());
        assertTrue(
                result.getMessage().contains("not json at all"),
                "an unparseable body should still surface verbatim rather than being discarded");
    }

    @Test
    void webClientResponseExceptionWithEmptyBodyFallsBackToExceptionMessage() {
        WebClientResponseException wcre =
                WebClientResponseException.create(
                        500,
                        "Internal Server Error",
                        HttpHeaders.EMPTY,
                        new byte[0],
                        StandardCharsets.UTF_8);

        UpstreamProviderException result = translator.translate(wcre);

        assertEquals(500, result.getUpstreamStatus());
        // getResponseBodyAsString() is blank, so the branch must fall back
        // to wcre.getMessage() rather than throwing or returning blank.
        assertTrue(result.getMessage().contains("500"));
    }

    @Test
    void webClientResponseException429RateLimitStatusIsPreservedVerbatim() {
        WebClientResponseException wcre =
                WebClientResponseException.create(
                        HttpStatus.TOO_MANY_REQUESTS.value(),
                        "Too Many Requests",
                        HttpHeaders.EMPTY,
                        "{\"error\":{\"message\":\"rate limited\"}}"
                                .getBytes(StandardCharsets.UTF_8),
                        StandardCharsets.UTF_8);

        UpstreamProviderException result = translator.translate(wcre);

        assertEquals(429, result.getUpstreamStatus());
        assertTrue(result.getMessage().contains("rate limited"));
    }

    // --- regex-fallback branch (AnthropicApi-style wrapped RuntimeException) ---

    @Test
    void responseExceptionShapedMessageIsParsedForStatusAndBody() {
        RuntimeException wrapped =
                new RuntimeException(
                        "Response exception, Status: [529 Service Unavailable], Body:[{\"error\":{\"message\":\"Overloaded\"}}]");

        UpstreamProviderException result = translator.translate(wrapped);

        assertEquals(529, result.getUpstreamStatus());
        assertTrue(result.getMessage().contains("Overloaded"));
    }

    @Test
    void responseExceptionShapedMessageWithPlainTextBodyUsesBodyVerbatim() {
        RuntimeException wrapped =
                new RuntimeException(
                        "Response exception, Status: [400 Bad Request], Body:[invalid request]");

        UpstreamProviderException result = translator.translate(wrapped);

        assertEquals(400, result.getUpstreamStatus());
        assertTrue(result.getMessage().contains("invalid request"));
    }

    @Test
    void messageNotMatchingResponseExceptionShapeFallsBackToDefaultStatusWithRawMessage() {
        RuntimeException wrapped = new RuntimeException("connection reset by peer");

        UpstreamProviderException result = translator.translate(wrapped);

        assertEquals(502, result.getUpstreamStatus());
        assertTrue(result.getMessage().contains("connection reset by peer"));
    }

    @Test
    void nullMessageThrowableFallsBackToGenericMessageWithoutThrowing() {
        RuntimeException noMessage = new RuntimeException((String) null);

        UpstreamProviderException result = translator.translate(noMessage);

        assertEquals(502, result.getUpstreamStatus());
        assertTrue(result.getMessage().contains("no further detail"));
    }

    @Test
    void nonNumericStatusInMessageDoesNotMatchPatternAndFallsBackToDefaultStatus() {
        // The status group requires exactly \d{3}, so a non-numeric status
        // (malformed upstream message) never matches at all - this exercises
        // the "!matcher.find()" branch via a message that looks superficially
        // close to the expected shape, distinct from a completely unrelated
        // message (covered above).
        RuntimeException wrapped =
                new RuntimeException("Response exception, Status: [abc], Body:[whatever]");

        UpstreamProviderException result = translator.translate(wrapped);

        assertEquals(502, result.getUpstreamStatus());
        assertTrue(result.getMessage().contains("Status: [abc]"));
    }

    // --- Spring AI RestClient branch (every non-streaming OpenAI-compat call) ---

    @Test
    void springAiRetryExceptionShapeIsRecoveredFromAnywhereInTheCauseChain() {
        // Spring AI's own RestClient error handler (RetryUtils#DEFAULT_RESPONSE_ERROR_HANDLER,
        // installed by OpenAiApi) throws NonTransient/TransientAiException whose ENTIRE
        // message is String.format("%s - %s", status, body) - and the model client above
        // it wraps that in a generic RuntimeException. Neither the WebClientResponseException
        // branch (that one is the streaming path) nor the Anthropic "Status: [...], Body:[...]"
        // shape matches, so this used to collapse to the generic 502 default and lose both
        // the real status and the provider's own text - exactly the hosted-pool Gemini 404.
        RuntimeException wrapped =
                new RuntimeException(
                        "Failed to generate content",
                        new RuntimeException(
                                "404 - {\"error\":{\"message\":\"models/ gemini-2.0-flash-lite is not found\"}}"));

        UpstreamProviderException result = translator.translate(wrapped);

        assertEquals(404, result.getUpstreamStatus());
        assertTrue(
                result.getMessage().contains("is not found"),
                "the provider's own reason must survive, not just the status");
    }

    @Test
    void credentialShapedSubstringsAreRedactedFromTheTechnicalMessage() {
        RuntimeException wrapped =
                new RuntimeException(
                        "404 Not Found from POST https://example.com/v1/chat/completions?key=AIzaSuperSecretValue");

        UpstreamProviderException result = translator.translate(wrapped);

        assertTrue(result.getMessage().contains("key=***"));
        assertFalse(
                result.getMessage().contains("AIzaSuperSecretValue"),
                "an API key that reached a message must never survive into a log or a response");
    }

    // --- Smart AI error explanation (generic-fallback branch only) ---------

    @Test
    void unstructuredFallbackUsesAiExplanationWhenTheExplainerProducesOne() {
        ErrorExplanationService explainer = org.mockito.Mockito.mock(ErrorExplanationService.class);
        org.mockito.Mockito.when(
                        explainer.explain(
                                org.mockito.Mockito.eq(502), org.mockito.Mockito.anyString()))
                .thenReturn(
                        java.util.Optional.of(
                                "The provider connection dropped mid-request. Please try again."));
        ProviderErrorTranslator aiTranslator =
                new ProviderErrorTranslator(com.agenticai.core.json.JsonMappers.plain(), explainer);

        UpstreamProviderException result =
                aiTranslator.translate(new RuntimeException("connection reset by peer"));

        assertEquals(
                "The provider connection dropped mid-request. Please try again.",
                result.getConsumerMessage());
        // The technical message is untouched by the explainer - logs keep the raw detail.
        assertTrue(result.getMessage().contains("connection reset by peer"));
    }

    @Test
    void unstructuredFallbackKeepsStaticMessageWhenExplainerReturnsEmpty() {
        // Empty is the explainer's contract for "budget blocked / pool down /
        // model declined" - the tenant must get the exact static classify()
        // text, never an error about the explainer.
        ErrorExplanationService explainer = org.mockito.Mockito.mock(ErrorExplanationService.class);
        org.mockito.Mockito.when(
                        explainer.explain(
                                org.mockito.Mockito.anyInt(), org.mockito.Mockito.anyString()))
                .thenReturn(java.util.Optional.empty());
        ProviderErrorTranslator aiTranslator =
                new ProviderErrorTranslator(com.agenticai.core.json.JsonMappers.plain(), explainer);

        UpstreamProviderException result =
                aiTranslator.translate(new RuntimeException("connection reset by peer"));

        assertEquals(
                new ProviderErrorTranslator(com.agenticai.core.json.JsonMappers.plain(), null)
                        .translate(new RuntimeException("connection reset by peer"))
                        .getConsumerMessage(),
                result.getConsumerMessage());
    }

    @Test
    void structuredBranchesNeverConsultTheExplainer() {
        // The explainer is scoped to the one branch where classify() has the
        // least to work with - a structured provider error (real status +
        // body) must not spend Smart AI budget.
        ErrorExplanationService explainer = org.mockito.Mockito.mock(ErrorExplanationService.class);
        ProviderErrorTranslator aiTranslator =
                new ProviderErrorTranslator(com.agenticai.core.json.JsonMappers.plain(), explainer);

        aiTranslator.translate(
                WebClientResponseException.create(
                        429,
                        "Too Many Requests",
                        HttpHeaders.EMPTY,
                        "{\"error\":{\"message\":\"rate limited\"}}"
                                .getBytes(StandardCharsets.UTF_8),
                        StandardCharsets.UTF_8));

        org.mockito.Mockito.verifyNoInteractions(explainer);
    }

    @Test
    void explainerThrowingIsSwallowedAndStaticMessageIsUsed() {
        // explain() is contractually non-throwing, but the translator's own
        // "never throws while translating" rule is belt-and-braces anyway.
        ErrorExplanationService explainer = org.mockito.Mockito.mock(ErrorExplanationService.class);
        org.mockito.Mockito.when(
                        explainer.explain(
                                org.mockito.Mockito.anyInt(), org.mockito.Mockito.anyString()))
                .thenThrow(new IllegalStateException("boom"));
        ProviderErrorTranslator aiTranslator =
                new ProviderErrorTranslator(com.agenticai.core.json.JsonMappers.plain(), explainer);

        UpstreamProviderException result =
                aiTranslator.translate(new RuntimeException("connection reset by peer"));

        assertEquals(502, result.getUpstreamStatus());
        assertTrue(result.getConsumerMessage().contains("Please try again"));
    }

    // --- translate() idempotence ------------------------------------------

    @Test
    void anAlreadyTranslatedExceptionPassesThroughUntouched() {
        // Without this, a second translate() pass (fallback gates classify
        // errors before their caller's own onErrorMap translates them again)
        // fell into the unstructured branch: status collapsed to 502, message
        // re-wrapped. Translation must be a boundary crossed at most once.
        UpstreamProviderException alreadyTranslated =
                new UpstreamProviderException(
                        404,
                        "The LLM provider rejected the request (HTTP 404): models/x is not found",
                        "The selected AI model isn't available from this provider anymore.",
                        "GEMINI");

        org.junit.jupiter.api.Assertions.assertSame(
                alreadyTranslated,
                translator.translate(
                        alreadyTranslated, com.agenticai.core.providers.ProviderName.GEMINI));
    }

    // --- isInvalidModelMessage (the ONE shared invalid-model phrase list) ---

    @Test
    void invalidModelPhrasesEveryMajorProviderActuallyUsesAreDetected() {
        // OpenAI / OpenRouter / Groq
        assertTrue(
                ProviderErrorTranslator.isInvalidModelMessage(
                        "The model `gpt-nonexistent` does not exist or you do not have access to it"));
        // Google (Gemini API)
        assertTrue(
                ProviderErrorTranslator.isInvalidModelMessage(
                        "models/gemini-1.0-pro is not found for API version v1beta"));
        // Structured error-code shape
        assertTrue(ProviderErrorTranslator.isInvalidModelMessage("model_not_found: no such model"));
        // Groq retirement wording
        assertTrue(
                ProviderErrorTranslator.isInvalidModelMessage(
                        "The model `llama3-8b-8192` has been decommissioned and is no longer supported"));
    }

    @Test
    void nonModelFailuresAreNeverClassifiedAsInvalidModel() {
        assertFalse(
                ProviderErrorTranslator.isInvalidModelMessage(
                        "rate_limit_exceeded: please slow down"));
        // "model" alone isn't enough - the not-found/retired half must be there too.
        assertFalse(
                ProviderErrorTranslator.isInvalidModelMessage("The model is currently overloaded"));
        // "not found" alone isn't enough either - a 404 endpoint isn't a model problem.
        assertFalse(ProviderErrorTranslator.isInvalidModelMessage("404 page not found"));
        assertFalse(ProviderErrorTranslator.isInvalidModelMessage(null));
    }

    @Test
    void modelIdIsExtractedFromBothQuotedAndModelsPathShapes() {
        assertEquals(
                "gpt-nonexistent",
                ProviderErrorTranslator.extractModelId(
                        "The model `gpt-nonexistent` does not exist"));
        assertEquals(
                "gemini-1.0-pro",
                ProviderErrorTranslator.extractModelId(
                        "models/gemini-1.0-pro is not found for API version v1beta"));
        org.junit.jupiter.api.Assertions.assertNull(
                ProviderErrorTranslator.extractModelId("model not found"));
    }

    // --- catalog-enriched invalid-model consumer message --------------------

    private static com.agenticai.core.providers.ProviderModelResponse catalogModel(String modelId) {
        return new com.agenticai.core.providers.ProviderModelResponse(
                modelId, modelId, null, false, false, false, null);
    }

    private static WebClientResponseException invalidModel404(String modelId) {
        return WebClientResponseException.create(
                404,
                "Not Found",
                HttpHeaders.EMPTY,
                ("{\"error\":{\"message\":\"The model `" + modelId + "` does not exist\"}}")
                        .getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8);
    }

    @Test
    void invalidModelMessageIsEnrichedWithTheProvidersCurrentModelList() {
        com.agenticai.core.providers.ProviderModelCatalogService catalog =
                org.mockito.Mockito.mock(
                        com.agenticai.core.providers.ProviderModelCatalogService.class);
        // Mockito's default answers already return Optional.empty() for
        // findModel/replacementForRetiredModel - i.e. "not in the catalog,
        // no known replacement" - which is the case under test.
        org.mockito.Mockito.when(
                        catalog.listModels(com.agenticai.core.providers.ProviderName.OPENAI))
                .thenReturn(
                        java.util.List.of(
                                catalogModel("gpt-4o-mini"), catalogModel("gpt-4o"),
                                catalogModel("gpt-4.1"), catalogModel("o4-mini")));
        ProviderErrorTranslator enriching =
                new ProviderErrorTranslator(
                        com.agenticai.core.json.JsonMappers.plain(), null, catalog);

        UpstreamProviderException result =
                enriching.translate(
                        invalidModel404("gpt-nonexistent"),
                        com.agenticai.core.providers.ProviderName.OPENAI);

        assertEquals(404, result.getUpstreamStatus());
        String consumer = result.getConsumerMessage();
        assertTrue(
                consumer.contains("Model 'gpt-nonexistent' is not in OPENAI's current model list"),
                consumer);
        assertTrue(consumer.contains("may have been retired"), consumer);
        // Top THREE only - never the whole catalog dumped into an error toast.
        assertTrue(
                consumer.contains("Available models include: gpt-4o-mini, gpt-4o, gpt-4.1."),
                consumer);
        assertFalse(consumer.contains("o4-mini"), consumer);
    }

    @Test
    void aKnownRetirementReplacementIsSuggestedByName() {
        com.agenticai.core.providers.ProviderModelCatalogService catalog =
                org.mockito.Mockito.mock(
                        com.agenticai.core.providers.ProviderModelCatalogService.class);
        org.mockito.Mockito.when(catalog.replacementForRetiredModel("gemini-2.0-flash-lite"))
                .thenReturn(java.util.Optional.of("gemini-2.5-flash-lite"));
        ProviderErrorTranslator enriching =
                new ProviderErrorTranslator(
                        com.agenticai.core.json.JsonMappers.plain(), null, catalog);

        UpstreamProviderException result =
                enriching.translate(
                        invalidModel404("gemini-2.0-flash-lite"),
                        com.agenticai.core.providers.ProviderName.GEMINI);

        assertTrue(
                result.getConsumerMessage()
                        .contains("Its suggested replacement is 'gemini-2.5-flash-lite'."),
                result.getConsumerMessage());
    }

    @Test
    void aModelTheCatalogStillListsKeepsTheStaticMessage() {
        // If the catalog says the model IS currently served, "not in the
        // current model list" would be a lie - the static message stands.
        com.agenticai.core.providers.ProviderModelCatalogService catalog =
                org.mockito.Mockito.mock(
                        com.agenticai.core.providers.ProviderModelCatalogService.class);
        org.mockito.Mockito.when(
                        catalog.findModel(
                                org.mockito.Mockito.eq(
                                        com.agenticai.core.providers.ProviderName.OPENAI),
                                org.mockito.Mockito.eq("gpt-4o")))
                .thenReturn(java.util.Optional.of(catalogModel("gpt-4o")));
        ProviderErrorTranslator enriching =
                new ProviderErrorTranslator(
                        com.agenticai.core.json.JsonMappers.plain(), null, catalog);

        UpstreamProviderException result =
                enriching.translate(
                        invalidModel404("gpt-4o"),
                        com.agenticai.core.providers.ProviderName.OPENAI);

        assertEquals(
                "The selected AI model isn't available from this provider anymore. Choose a different model for this agent.",
                result.getConsumerMessage());
    }

    @Test
    void aCatalogFailureNeverBreaksTranslationItself() {
        com.agenticai.core.providers.ProviderModelCatalogService catalog =
                org.mockito.Mockito.mock(
                        com.agenticai.core.providers.ProviderModelCatalogService.class);
        org.mockito.Mockito.when(
                        catalog.findModel(
                                org.mockito.Mockito.any(), org.mockito.Mockito.anyString()))
                .thenThrow(new IllegalStateException("catalog database down"));
        ProviderErrorTranslator enriching =
                new ProviderErrorTranslator(
                        com.agenticai.core.json.JsonMappers.plain(), null, catalog);

        UpstreamProviderException result =
                enriching.translate(
                        invalidModel404("gpt-nonexistent"),
                        com.agenticai.core.providers.ProviderName.OPENAI);

        assertEquals(404, result.getUpstreamStatus());
        assertEquals(
                "The selected AI model isn't available from this provider anymore. Choose a different model for this agent.",
                result.getConsumerMessage());
    }

    @Test
    void noInjectedCatalogOrNoProviderIdentityKeepsTheStaticMessage() {
        // The 2-arg constructor (no catalog) and a provider-less call site must
        // both behave exactly as before the enrichment existed.
        UpstreamProviderException noCatalog =
                translator.translate(invalidModel404("gpt-nonexistent"));
        assertEquals(
                "The selected AI model isn't available from this provider anymore. Choose a different model for this agent.",
                noCatalog.getConsumerMessage());
    }
}
