package com.agenticai.core.providers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The hand-maintained seed catalog is the only model source for every provider the platform-wide
 * refresh job cannot authenticate against, so its invariants are data-quality checks: every seeded
 * provider resolves to a real {@link ProviderName}, model ids are unique within a provider (the
 * {@code provider_model_catalog} table has a UNIQUE constraint on the pair - see the SARVAM comment
 * in the catalog for the incident a duplicate caused), and no row ships a blank id/name or a
 * non-positive context window.
 */
class ProviderModelSeedCatalogTest {

    @Test
    void everySeededProviderYieldsAtLeastOneUsableRow() {
        assertFalse(ProviderModelSeedCatalog.providers().isEmpty());
        for (ProviderName provider : ProviderModelSeedCatalog.providers()) {
            List<ProviderModelCatalogEntry> entries = ProviderModelSeedCatalog.entriesFor(provider);
            assertFalse(entries.isEmpty(), provider + " is seeded but yields no rows");
            Set<String> ids = new HashSet<>();
            for (ProviderModelCatalogEntry entry : entries) {
                assertEquals(provider, entry.getProviderName());
                assertNotNull(entry.getModelId());
                assertFalse(entry.getModelId().isBlank(), provider + " has a blank model id");
                assertFalse(
                        entry.getDisplayName() == null || entry.getDisplayName().isBlank(),
                        provider + "/" + entry.getModelId() + " has no display name");
                assertTrue(
                        entry.getContextWindow() == null || entry.getContextWindow() > 0,
                        provider + "/" + entry.getModelId() + " has a non-positive context window");
                assertNotNull(entry.getUpdatedAt(), provider + "/" + entry.getModelId());
                assertTrue(
                        ids.add(entry.getModelId()),
                        provider
                                + " lists model id "
                                + entry.getModelId()
                                + " twice - "
                                + "UNIQUE(provider_name, model_id) makes that a failed refresh");
            }
        }
    }

    @Test
    void theWellKnownSeededProvidersArePresent() {
        Set<ProviderName> seeded = ProviderModelSeedCatalog.providers();
        for (ProviderName provider :
                new ProviderName[] {
                    ProviderName.ANTHROPIC,
                    ProviderName.OPENAI,
                    ProviderName.GEMINI,
                    ProviderName.GROK,
                    ProviderName.SARVAM,
                    ProviderName.GOOGLE_VERTEX,
                    ProviderName.AZURE_OPENAI,
                    ProviderName.AWS_BEDROCK,
                    ProviderName.OLLAMA,
                    ProviderName.MISTRAL,
                    ProviderName.DEEPSEEK,
                    ProviderName.GROQ,
                    ProviderName.META
                }) {
            assertTrue(seeded.contains(provider), provider + " dropped out of the seed catalog");
        }
        // OpenRouter is fetched live with no key, LOCAL_OPENAI is
        // tenant-hosted, PLATFORM_HOSTED is not a real vendor - none is seeded.
        assertFalse(seeded.contains(ProviderName.OPENROUTER));
        assertFalse(seeded.contains(ProviderName.LOCAL_OPENAI));
        assertFalse(seeded.contains(ProviderName.PLATFORM_HOSTED));
    }

    @Test
    void unseededProvidersYieldAnEmptyListNotAnError() {
        assertTrue(ProviderModelSeedCatalog.entriesFor(ProviderName.OPENROUTER).isEmpty());
        assertTrue(ProviderModelSeedCatalog.entriesFor(ProviderName.LOCAL_OPENAI).isEmpty());
    }

    @Test
    void seedRowsCarryTheirModalityFlagsThrough() {
        ProviderModelCatalogEntry gemini =
                ProviderModelSeedCatalog.entriesFor(ProviderName.GEMINI).stream()
                        .filter(e -> e.getModelId().equals("gemini-2.5-flash"))
                        .findFirst()
                        .orElseThrow();
        assertTrue(gemini.isSupportsImageInput());
        assertTrue(gemini.isSupportsAudioInput());
        assertTrue(gemini.isSupportsVideoInput());
        ProviderModelCatalogEntry sarvam =
                ProviderModelSeedCatalog.entriesFor(ProviderName.SARVAM).get(0);
        assertFalse(sarvam.isSupportsImageInput());
        assertFalse(sarvam.isSupportsAudioInput());
        assertFalse(sarvam.isSupportsVideoInput());
    }

    @Test
    void geminiHasNoFlashLiteIdSoAutoSelectionCannotLandOnAThoughtSignatureModel() {
        // See the long comment on the GEMINI block in ProviderModelSeedCatalog:
        // any "*-flash-lite" id anywhere in this list wins hosted-pool
        // auto-selection outright, and Gemini flash-lite models need a
        // thought_signature the OpenAI-compat path strips.
        for (ProviderModelCatalogEntry e :
                ProviderModelSeedCatalog.entriesFor(ProviderName.GEMINI)) {
            assertFalse(e.getModelId().contains("flash-lite"), e.getModelId());
        }
        // gemini-2.5-flash must stay first so the "flash" keyword pick lands on it.
        assertEquals(
                "gemini-2.5-flash",
                ProviderModelSeedCatalog.entriesFor(ProviderName.GEMINI).get(0).getModelId());
    }

    @Test
    void metaOpenWeightModelsAreListedForOllamaAndGroq() {
        Set<String> ollama = idsOf(ProviderName.OLLAMA);
        for (String id :
                new String[] {"llama3.1:8b", "llama3.3:70b", "llama4:16x17b", "muse-glimmer:30b"}) {
            assertTrue(ollama.contains(id), "OLLAMA seed lost Meta model " + id);
        }
        assertTrue(
                idsOf(ProviderName.GROQ).contains("meta-models/muse-spark-open"),
                "GROQ seed lost Muse Spark Open");
    }

    @Test
    void museGlimmerAndLlama4AcceptImagesButTheOlderLlamasDoNot() {
        for (ProviderModelCatalogEntry e :
                ProviderModelSeedCatalog.entriesFor(ProviderName.OLLAMA)) {
            boolean multimodal =
                    e.getModelId().equals("muse-glimmer:30b")
                            || e.getModelId().equals("llama4:16x17b");
            if (multimodal) {
                assertTrue(e.isSupportsImageInput(), e.getModelId());
            }
            if (e.getModelId().equals("llama3.1:8b") || e.getModelId().equals("llama3.3:70b")) {
                assertFalse(e.isSupportsImageInput(), e.getModelId());
            }
        }
    }

    @Test
    void museSparkOpenNeverWinsHostedPoolAutoSelectionOnGroq() {
        // The catalog comment promises that Muse Spark Open's id holds none of the hosted pool's
        // auto-pick keywords, so adding it cannot change which Groq model auto-selection picks
        // (the fast llama-3.1-8b-instant). Prove it against the real picker, not by inspection.
        List<ProviderModelResponse> groq =
                ProviderModelSeedCatalog.entriesFor(ProviderName.GROQ).stream()
                        .map(ProviderModelResponse::from)
                        .toList();
        assertTrue(groq.stream().anyMatch(m -> m.modelId().equals("meta-models/muse-spark-open")));
        assertEquals(
                "llama-3.1-8b-instant",
                HostedPoolRequestResolver.pickBestModel(ProviderName.GROQ, groq));
        for (String keyword : new ProviderModelDefaultsProperties().getHostedPoolAutoKeywords()) {
            assertFalse(
                    "meta-models/muse-spark-open".contains(keyword.toLowerCase(Locale.ROOT)),
                    "Muse Spark Open's id matches auto-pick keyword '" + keyword + "'");
        }
    }

    @Test
    void thePlatformModelApiStillOffersTheMuseSparkLineWithTheNewestFirst() {
        List<String> meta =
                ProviderModelSeedCatalog.entriesFor(ProviderName.META).stream()
                        .map(ProviderModelCatalogEntry::getModelId)
                        .toList();
        assertEquals("muse-spark-1.3", meta.get(0));
        assertTrue(meta.containsAll(List.of("muse-spark-1.2", "muse-spark-1.1")));
    }

    private static Set<String> idsOf(ProviderName provider) {
        Set<String> ids = new HashSet<>();
        for (ProviderModelCatalogEntry e : ProviderModelSeedCatalog.entriesFor(provider)) {
            ids.add(e.getModelId());
        }
        return ids;
    }
}
