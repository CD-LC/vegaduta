package com.agenticai.core.agents.modality;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.providers.ProviderModelCatalogService;
import com.agenticai.core.providers.ProviderModelResponse;
import com.agenticai.core.providers.ProviderName;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * Pattern-table + source-priority coverage for {@link ModelModalitiesService}
 * (multimodal playground contract): the conservative name heuristics, the
 * catalog-wins-when-present rule for image/audio/video, the config override
 * hook, and the all-false default for anything unknown.
 */
class ModelModalitiesServiceTest {

    private ProviderModelCatalogService catalog;
    private MockEnvironment environment;

    @BeforeEach
    void setUp() {
        catalog = mock(ProviderModelCatalogService.class);
        // Default: the catalog has never heard of the model - heuristics only.
        when(catalog.findModel(any(), any())).thenReturn(Optional.empty());
        environment = new MockEnvironment();
    }

    private ModelModalitiesService service() {
        return new ModelModalitiesService(catalog, environment);
    }

    // --- name-pattern heuristics (catalog miss) ---

    @Test
    void gpt4oIsImageOnly() {
        ModelModalities m = service().resolve(ProviderName.OPENAI, "gpt-4o");
        assertEquals(new ModelModalities(true, false, false, false), m);
    }

    @Test
    void gpt4oAudioPreviewUnionsImageAndAudioRules() {
        ModelModalities m = service().resolve(ProviderName.OPENAI, "gpt-4o-audio-preview");
        assertEquals(new ModelModalities(true, true, false, false), m);
    }

    @Test
    void claudeFamiliesAreImageCapable() {
        assertTrue(service().resolve(ProviderName.ANTHROPIC, "claude-sonnet-5").image());
        assertTrue(service().resolve(ProviderName.ANTHROPIC, "claude-3-5-haiku-20241022").image());
        assertTrue(service().resolve(ProviderName.ANTHROPIC, "claude-opus-4-8").image());
    }

    @Test
    void geminiGetsAllFourModalities() {
        ModelModalities m = service().resolve(ProviderName.GEMINI, "gemini-2.5-pro");
        assertEquals(new ModelModalities(true, true, true, true), m);
    }

    @Test
    void openWeightVisionFamiliesMatch() {
        assertTrue(service().resolve(ProviderName.OPENROUTER, "llava-v1.6-34b").image());
        assertTrue(service().resolve(ProviderName.OPENROUTER, "pixtral-12b").image());
        assertTrue(service().resolve(ProviderName.OPENROUTER, "qwen2-vl-72b-instruct").image());
    }

    @Test
    void slashPrefixedIdsMatchOnTheirLastSegment() {
        // OpenRouter-style "vendor/model" id, catalog miss - the heuristic
        // still recognizes the model half.
        assertTrue(service().resolve(ProviderName.OPENROUTER, "openai/gpt-4o").image());
        assertTrue(service().resolve(ProviderName.OPENROUTER, "google/gemini-2.0-flash-001").document());
    }

    @Test
    void unknownModelsAreTextOnly() {
        assertEquals(ModelModalities.NONE, service().resolve(ProviderName.SARVAM, "sarvam-105b"));
        assertEquals(ModelModalities.NONE, service().resolve(ProviderName.OPENAI, "gpt-3.5-turbo"));
        assertEquals(ModelModalities.NONE, service().resolve(ProviderName.OPENAI, null));
        assertEquals(ModelModalities.NONE, service().resolve(ProviderName.OPENAI, "  "));
    }

    // --- catalog wins for image/audio/video when the model is known ---

    @Test
    void catalogFlagsOverrideHeuristicsWhenEntryExists() {
        // The catalog explicitly says this model takes image+audio but the
        // name alone would only have granted image.
        when(catalog.findModel(ProviderName.OPENAI, "gpt-5.1"))
            .thenReturn(Optional.of(model("gpt-5.1", true, true, false)));
        ModelModalities m = service().resolve(ProviderName.OPENAI, "gpt-5.1");
        assertEquals(new ModelModalities(true, true, false, false), m);
    }

    @Test
    void catalogSayingTextOnlyBeatsAnOptimisticNamePattern() {
        // grok-3 matches no rule anyway, but force the sharper case: a model
        // whose NAME looks vision-y while the catalog knows it isn't.
        when(catalog.findModel(ProviderName.OPENAI, "gpt-4o-mini-text"))
            .thenReturn(Optional.of(model("gpt-4o-mini-text", false, false, false)));
        ModelModalities m = service().resolve(ProviderName.OPENAI, "gpt-4o-mini-text");
        assertFalse(m.image());
        assertFalse(m.any());
    }

    @Test
    void geminiKeepsHeuristicDocumentFlagEvenWithACatalogEntry() {
        // The catalog doesn't track document support - the heuristic fills it.
        when(catalog.findModel(ProviderName.GEMINI, "gemini-3.5-flash"))
            .thenReturn(Optional.of(model("gemini-3.5-flash", true, true, true)));
        ModelModalities m = service().resolve(ProviderName.GEMINI, "gemini-3.5-flash");
        assertEquals(new ModelModalities(true, true, true, true), m);
    }

    @Test
    void museSparkGetsAllFourModalitiesIncludingDocumentDirectAndViaOpenRouter() {
        // The catalog never tracks "document" - only the heuristic grants it,
        // so a catalog entry must not strip PDF input from Muse Spark.
        when(catalog.findModel(ProviderName.META, "muse-spark-1.3"))
            .thenReturn(Optional.of(model("muse-spark-1.3", true, true, true)));
        assertEquals(new ModelModalities(true, true, true, true), service().resolve(ProviderName.META, "muse-spark-1.3"));
        assertEquals(new ModelModalities(true, true, true, true),
            service().resolve(ProviderName.META, "muse-spark-1.3-contributor"));
        assertTrue(service().resolve(ProviderName.OPENROUTER, "meta/muse-spark-1.3").document());
    }

    @Test
    void catalogFailureDegradesToHeuristicsInsteadOfThrowing() {
        when(catalog.findModel(any(), any())).thenThrow(new IllegalStateException("db down"));
        assertTrue(service().resolve(ProviderName.OPENAI, "gpt-4o").image());
    }

    // --- config override hook ---

    @Test
    void configOverrideWinsOverEverything() {
        environment.setProperty("app.chat.modalities.overrides[customvision*]", "image,document");
        // Catalog would say text-only; the operator override wins.
        when(catalog.findModel(ProviderName.OPENROUTER, "customvision-9b"))
            .thenReturn(Optional.of(model("customvision-9b", false, false, false)));
        ModelModalities m = service().resolve(ProviderName.OPENROUTER, "customvision-9b");
        assertEquals(new ModelModalities(true, false, false, true), m);
    }

    @Test
    void configOverrideCanForceTextOnly() {
        environment.setProperty("app.chat.modalities.overrides[gemini-2.5-pro]", "none");
        assertEquals(ModelModalities.NONE, service().resolve(ProviderName.GEMINI, "gemini-2.5-pro"));
    }

    private static ProviderModelResponse model(String id, boolean image, boolean audio, boolean video) {
        return new ProviderModelResponse(id, id, 128_000, image, audio, video, null);
    }
}
