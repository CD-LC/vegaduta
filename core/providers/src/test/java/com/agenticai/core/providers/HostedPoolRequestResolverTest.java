package com.agenticai.core.providers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.providers.PlatformHostedLlmProviderService.ResolvedRequest;
import com.agenticai.core.providers.validation.ProviderValidationException;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link HostedPoolRequestResolver} fills in whatever an admin left blank:
 * {@code providerName} derives the vendor's known endpoint, a missing {@code
 * model} is auto-picked from the model catalog (cost preference first, the
 * fast/cheap keyword list second, the catalog's documented default last), and
 * anything the admin actually typed always wins. Also covers the upsert
 * request record's own normalization rules.
 */
class HostedPoolRequestResolverTest {

    private ProviderModelCatalogService catalog;
    private HostedPoolRequestResolver resolver;

    @BeforeEach
    void setUp() {
        catalog = mock(ProviderModelCatalogService.class);
        resolver = new HostedPoolRequestResolver(catalog);
    }

    private static PlatformHostedLlmProviderUpsertRequest request(
            String baseUrl, String model, String completionsPath, ProviderName providerName) {
        return new PlatformHostedLlmProviderUpsertRequest(
            "Pool row", baseUrl, model, "key", true, 0, null, completionsPath, providerName);
    }

    private static ProviderModelResponse model(String id) {
        return new ProviderModelResponse(id, id, 128_000, false, false, false, null);
    }

    @Test
    void aProviderOutsideTheCompatibleSetIsRejectedOutright() {
        ProviderValidationException e = assertThrows(
            ProviderValidationException.class,
            () -> resolver.resolve(request(null, null, null, ProviderName.ANTHROPIC)));
        assertTrue(e.getMessage().contains("isn't supported for the hosted pool"), e.getMessage());
    }

    @Test
    void aCompatibleProviderFillsItsKnownEndpointWhenBaseUrlIsBlank() {
        ResolvedRequest resolved = resolver.resolve(request(null, "my-model", null, ProviderName.GROQ));
        assertEquals("https://api.groq.com/openai", resolved.baseUrl());
        assertNull(resolved.completionsPath());
        assertEquals("my-model", resolved.model());
        assertFalse(resolved.modelAutoSelected());
    }

    @Test
    void geminiFillsBothTheBaseUrlAndItsNonStandardCompletionsPath() {
        ResolvedRequest resolved = resolver.resolve(request(null, "m", null, ProviderName.GEMINI));
        assertEquals("https://generativelanguage.googleapis.com", resolved.baseUrl());
        assertEquals("/v1beta/openai/chat/completions", resolved.completionsPath());
    }

    @Test
    void anExplicitBaseUrlAndCompletionsPathAlwaysWinOverTheProviderDefaults() {
        ResolvedRequest resolved = resolver.resolve(
            request("https://proxy.example.com", "m", "/custom/path", ProviderName.GROQ));
        assertEquals("https://proxy.example.com", resolved.baseUrl());
        assertEquals("/custom/path", resolved.completionsPath());
    }

    @Test
    void aBlankBaseUrlWithNoProviderIsRejected() {
        ProviderValidationException e = assertThrows(
            ProviderValidationException.class,
            () -> resolver.resolve(request(" ", "m", null, null)));
        assertTrue(e.getMessage().contains("base URL is required"), e.getMessage());
    }

    @Test
    void aBlankModelWithNoProviderIsRejected() {
        ProviderValidationException e = assertThrows(
            ProviderValidationException.class,
            () -> resolver.resolve(request("https://x.example", null, null, null)));
        assertTrue(e.getMessage().contains("model id is required"), e.getMessage());
    }

    @Test
    void aBlankModelIsAutoSelectedAndFlaggedAsSuch() {
        when(catalog.listModels(ProviderName.GROQ))
            .thenReturn(List.of(model("llama-3.3-70b-versatile"), model("llama-3.1-8b-instant")));
        when(catalog.costPreferredModel(eq(ProviderName.GROQ), any())).thenReturn(Optional.empty());

        ResolvedRequest resolved = resolver.resolve(request(null, null, null, ProviderName.GROQ));
        assertEquals("llama-3.1-8b-instant", resolved.model(), "the 8b keyword match wins");
        assertTrue(resolved.modelAutoSelected());
    }

    @Test
    void aCostPreferredModelBeatsTheKeywordScan() {
        when(catalog.listModels(ProviderName.MISTRAL))
            .thenReturn(List.of(model("mistral-large-latest"), model("open-mistral-nemo")));
        when(catalog.costPreferredModel(eq(ProviderName.MISTRAL), any()))
            .thenReturn(Optional.of("mistral-large-latest"));

        assertEquals("mistral-large-latest", resolver.autoSelectModel(ProviderName.MISTRAL));
    }

    @Test
    void anEmptyCatalogFailsAutoSelectionLoudly() {
        when(catalog.listModels(ProviderName.DEEPSEEK)).thenReturn(List.of());
        when(catalog.costPreferredModel(eq(ProviderName.DEEPSEEK), any())).thenReturn(Optional.empty());
        ProviderValidationException e = assertThrows(
            ProviderValidationException.class,
            () -> resolver.autoSelectModel(ProviderName.DEEPSEEK));
        assertTrue(e.getMessage().contains("catalog is currently empty"), e.getMessage());
    }

    // --- pickBestModel -------------------------------------------------------

    @Test
    void pickBestModelPrefersTheFastCheapKeywordsInOrder() {
        List<ProviderModelResponse> models =
            List.of(model("huge-pro"), model("tiny-8b"), model("tiny-mini"));
        // "mini" is an earlier keyword than "8b" - it scans the WHOLE list first.
        assertEquals("tiny-mini", HostedPoolRequestResolver.pickBestModel(models));
        assertEquals("tiny-8b",
            HostedPoolRequestResolver.pickBestModel(List.of(model("huge-pro"), model("tiny-8b"))));
    }

    @Test
    void pickBestModelFallsBackToTheConfiguredProviderDefaultThenTheFirstRow() {
        // META's catalog lists oldest first; the hand-picked default still wins.
        List<ProviderModelResponse> meta =
            List.of(model("muse-spark-1.1"), model("muse-spark-1.3"));
        assertEquals("muse-spark-1.3",
            HostedPoolRequestResolver.pickBestModel(ProviderName.META, meta));
        // No keyword match and no configured default: alphabetical-first stands.
        assertEquals("whatever-first",
            HostedPoolRequestResolver.pickBestModel(null, List.of(model("whatever-first"), model("z"))));
        assertNull(HostedPoolRequestResolver.pickBestModel(null, List.of()));
    }

    // --- PlatformHostedLlmProviderUpsertRequest -------------------------------

    @Test
    void theRequestTrimsFreeTextAndCollapsesBlankToNull() {
        PlatformHostedLlmProviderUpsertRequest r = new PlatformHostedLlmProviderUpsertRequest(
            "  label ", "  https://x  ", "  m  ", "  key  ", true, 0, "  ", "  /p  ", ProviderName.GROQ);
        assertEquals("label", r.label());
        assertEquals("https://x", r.baseUrl());
        assertEquals("m", r.model());
        assertEquals("key", r.apiKey());
        assertNull(r.region(), "a blank optional field collapses to null");
        assertEquals("/p", r.completionsPath());
    }

    @Test
    void zeroCostDerivesFromOllamaUnlessOverridden() {
        assertTrue(new PlatformHostedLlmProviderUpsertRequest(
            "l", "b", "m", "k", true, 0, null, null, ProviderName.OLLAMA).effectiveZeroCost());
        assertFalse(new PlatformHostedLlmProviderUpsertRequest(
            "l", "b", "m", "k", true, 0, null, null, ProviderName.GROQ).effectiveZeroCost());
        PlatformHostedLlmProviderUpsertRequest overridden = new PlatformHostedLlmProviderUpsertRequest(
            "l", "b", "m", "k", true, 0, null, null, ProviderName.GROQ,
            null, null, null, Boolean.TRUE, null, null);
        assertTrue(overridden.effectiveZeroCost(), "an explicit TRUE wins over the provider default");
    }

    @Test
    void onlyVertexAdcRowsSkipTheApiKeyRequirement() {
        assertTrue(new PlatformHostedLlmProviderUpsertRequest(
            "l", "b", "m", "k", true, 0, null, null, ProviderName.GOOGLE_VERTEX,
            null, null, null, null, null, null).requiresApiKey());
        assertFalse(new PlatformHostedLlmProviderUpsertRequest(
            "l", "b", "m", null, true, 0, null, null, ProviderName.GOOGLE_VERTEX,
            PlatformHostedLlmAuthType.VERTEX_ADC, "p-1", null, null, null, null).requiresApiKey());
        assertTrue(new PlatformHostedLlmProviderUpsertRequest(
            "l", "b", "m", "json", true, 0, null, null, ProviderName.GOOGLE_VERTEX,
            PlatformHostedLlmAuthType.VERTEX_SERVICE_ACCOUNT, "p-1", null, null, null, null).requiresApiKey());
    }

    @Test
    void applyOverridesWritesOnlyTheRouterAndSmallModelFlags() {
        PlatformHostedLlmProvider entity =
            new PlatformHostedLlmProvider("l", "b", "m", null, true, 0, null, null);
        new PlatformHostedLlmProviderUpsertRequest(
            "l", "b", "m", "k", true, 0, null, null, null,
            null, null, null, null, Boolean.TRUE, Boolean.FALSE)
            .applyOverridesTo(entity);
        assertEquals(Boolean.TRUE, entity.getToolRouterEnabled());
        assertEquals(Boolean.FALSE, entity.getSmallModel());
    }
}
