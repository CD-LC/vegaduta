package com.agenticai.core.providers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * {@link ProviderModelResponse}'s contract with the model picker: {@code capabilitySummary} is
 * never blank (the LLM-generated blurb wins when the generation job has written one; otherwise a
 * deterministic template built only from fields already on the row), and {@code source} defaults to
 * {@code "CATALOG"} on the pre-live-fetch constructor shape.
 */
class ProviderModelResponseTest {

    private static ProviderModelCatalogEntry entry(String modelId, Integer contextWindow) {
        return new ProviderModelCatalogEntry(
                ProviderName.OPENAI,
                modelId,
                modelId + " display",
                contextWindow,
                true,
                false,
                false);
    }

    @Test
    void fromPrefersTheGeneratedSummaryWhenOneIsCached() {
        ProviderModelCatalogEntry e = entry("gpt-4o", 128_000);
        e.setCapabilitySummary("  Writes sonnets in assembly language.  ");
        ProviderModelResponse r = ProviderModelResponse.from(e);
        assertEquals("  Writes sonnets in assembly language.  ", r.capabilitySummary());
    }

    @Test
    void fromFallsBackToTheDeterministicTemplateOnNullOrBlankSummary() {
        ProviderModelResponse r = ProviderModelResponse.from(entry("gpt-4o", 128_000));
        assertEquals(
                "Text in, text out - also accepts images as input. 128K-token context window.",
                r.capabilitySummary());

        ProviderModelCatalogEntry blank = entry("gpt-4o", 128_000);
        blank.setCapabilitySummary("   ");
        assertEquals(r.capabilitySummary(), ProviderModelResponse.from(blank).capabilitySummary());
    }

    @Test
    void theTemplateListsEveryAcceptedInputModalityAndFormatsWindows() {
        assertEquals(
                "Text in, text out - also accepts images, audio, video as input. 1M-token context window.",
                ProviderModelResponse.summarize(1_000_000, true, true, true));
        assertEquals(
                "Text in, text out - also accepts audio as input.",
                ProviderModelResponse.summarize(null, false, true, false));
        assertEquals("Text in, text out.", ProviderModelResponse.summarize(0, false, false, false));
        assertTrue(
                ProviderModelResponse.summarize(400_000, false, false, false)
                        .contains("400K-token"));
        assertTrue(ProviderModelResponse.summarize(500, false, false, false).contains("500-token"));
        // Non-round windows are rounded, never truncated oddly.
        assertTrue(
                ProviderModelResponse.summarize(1_500_000, false, false, false).contains("2M-token")
                        || ProviderModelResponse.summarize(1_500_000, false, false, false)
                                .contains("M-token"));
    }

    @Test
    void theLegacyConstructorDefaultsSourceToCatalogAndLiveFieldsToNull() {
        ProviderModelResponse r = ProviderModelResponse.from(entry("gpt-4o", 128_000));
        assertEquals(ProviderModelResponse.SOURCE_CATALOG, r.source());
        assertNull(r.description());
        assertNull(r.deprecated());
        assertEquals("gpt-4o", r.modelId());
        assertEquals("gpt-4o display", r.displayName());
        assertEquals(128_000, r.contextWindow());
        assertTrue(r.supportsImageInput());
        assertTrue(!r.supportsAudioInput());
    }

    @Test
    void catalogEntryExposesTheFieldsFromConstructs() {
        ProviderModelCatalogEntry e =
                new ProviderModelCatalogEntry(
                        ProviderName.ANTHROPIC,
                        "claude-haiku-4-5-20251001",
                        "Claude Haiku 4.5",
                        200_000);
        assertEquals(ProviderName.ANTHROPIC, e.getProviderName());
        assertEquals("claude-haiku-4-5-20251001", e.getModelId());
        assertEquals("Claude Haiku 4.5", e.getDisplayName());
        assertEquals(200_000, e.getContextWindow());
        assertTrue(
                !e.isSupportsImageInput()
                        && !e.isSupportsAudioInput()
                        && !e.isSupportsVideoInput());
        assertNull(e.getId());
        assertNull(e.getCapabilitySummary());
    }
}
