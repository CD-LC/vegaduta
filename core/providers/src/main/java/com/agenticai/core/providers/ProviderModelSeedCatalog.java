package com.agenticai.core.providers;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The hand-maintained model catalog for every provider {@link ProviderModelCatalogService} cannot
 * fetch live (all but OpenRouter: their models-list endpoints need an API key, and the catalog job
 * is platform-wide). Reference data only; the service refreshes it into {@code
 * control_plane.provider_model_catalog} through the same pipeline as the live OpenRouter fetch.
 */
final class ProviderModelSeedCatalog {

    private ProviderModelSeedCatalog() {}

    /**
     * Curated fallback for providers this platform-wide job can't authenticate against - see
     * ProviderModelCatalogService's javadoc. Anthropic's three current models per the July 2026
     * lineup; Gemini/Grok/Sarvam best-known public model ids as of the same date. Update this list
     * by hand when a vendor ships a new model faster than it's convenient to wait for a code change
     * - it's the same kind of manually-maintained reference data as {@code PROVIDER_INFO} in the
     * web console's ProvidersPanel.
     */
    // Modality flags below reflect each vendor's actual chat-input
    // capability, not what a *separate* endpoint of theirs can do - e.g.
    // Sarvam's chat models are text-only even though this platform also
    // calls Sarvam's dedicated STT/TTS endpoints as tools (SarvamTranscribe/
    // SarvamSpeak) elsewhere; that's a different code path from "the chat
    // model itself accepts an audio attachment", so it's flagged false here.
    // Update by hand alongside SEED_MODELS itself when a vendor changes a
    // model's supported input types.
    private static final Map<ProviderName, List<SeedModel>> HOSTED_POOL_SEEDS =
            Map.ofEntries(
                    Map.entry(
                            ProviderName.ANTHROPIC,
                            List.of(
                                    // claude-fable-5 / claude-opus-5 added in the Aug 2026 catalog
                                    // repair (same pass that replaced the retired Grok 3/4 line -
                                    // see V182). Existing entries kept: claude-opus-4-8 is still
                                    // the
                                    // EXPERT tier pick in AgentService.TIER_MODELS and none of the
                                    // three below have a confirmed retirement date.
                                    new SeedModel(
                                            "claude-fable-5",
                                            "Claude Fable 5",
                                            200_000,
                                            true,
                                            false,
                                            false),
                                    new SeedModel(
                                            "claude-opus-5",
                                            "Claude Opus 5",
                                            200_000,
                                            true,
                                            false,
                                            false),
                                    new SeedModel(
                                            "claude-opus-4-8",
                                            "Claude Opus 4.8",
                                            200_000,
                                            true,
                                            false,
                                            false),
                                    new SeedModel(
                                            "claude-sonnet-5",
                                            "Claude Sonnet 5",
                                            200_000,
                                            true,
                                            false,
                                            false),
                                    new SeedModel(
                                            "claude-haiku-4-5-20251001",
                                            "Claude Haiku 4.5",
                                            200_000,
                                            true,
                                            false,
                                            false))),
                    Map.entry(
                            ProviderName.OPENAI,
                            List.of(
                                    new SeedModel("gpt-5.1", "GPT-5.1", 400_000, true, true, false),
                                    new SeedModel(
                                            "gpt-5.1-mini",
                                            "GPT-5.1 Mini",
                                            400_000,
                                            true,
                                            true,
                                            false),
                                    new SeedModel("gpt-4o", "GPT-4o", 128_000, true, true, false),
                                    new SeedModel(
                                            "gpt-4o-mini",
                                            "GPT-4o Mini",
                                            128_000,
                                            true,
                                            true,
                                            false),
                                    new SeedModel(
                                            "o4-mini",
                                            "o4-mini (reasoning)",
                                            200_000,
                                            true,
                                            false,
                                            false))),
                    // Live-verified 2026-07-31 directly against the OpenAI-compat endpoint
                    // (generativelanguage.googleapis.com/v1beta/openai/chat/completions)
                    // with the platform's actual hosted-pool key, after two prior fixes
                    // to this list both turned out wrong (see V165/V167 migrations):
                    //   - Every gemini-2.0-flash* model (with or without a version/-lite
                    //     suffix) now 404s there: "This model models/gemini-2.0-flash is
                    //     no longer available. ... use the Interactions API." The GET
                    //     /v1beta/openai/models listing STILL lists these ids - Google
                    //     has not cleaned up the catalog endpoint to match, so the models
                    //     list is NOT a reliable signal for what chat/completions will
                    //     accept. Do not re-add any 2.0.x model based on it appearing
                    //     there again without a live POST test.
                    //   - gemini-2.5-flash returned a real HTTP 200 completion - kept
                    //     first/default. gemini-2.5-pro is the same generation and is
                    //     kept as the deep-reasoning slot, but has not been individually
                    //     re-verified this pass.
                    //   - Gemini 3+ models (gemini-3.5-flash) require thought_signature
                    //     echoed back in tool-result follow-up requests; Spring AI's
                    //     OpenAiChatModel (what HostedLlmChatClientFactory uses for the
                    //     OpenAI-compat path) silently strips it, causing HTTP 400 on
                    //     every tool-using turn. gemini-3.5-flash is kept in the catalog
                    //     (BYOK Gemini uses the native GoogleGenAiChatModel client, which
                    //     is unaffected) but NOT first, so hosted-pool auto-selection
                    //     never lands on it. gemini-3.1-flash-lite is deliberately
                    //     EXCLUDED entirely, not just de-prioritized: pickBestModel()
                    //     checks AUTO_MODEL_PREFERENCE_KEYWORDS' "flash-lite" keyword
                    //     BEFORE "flash", scanning the WHOLE model list for that keyword
                    //     first regardless of list order - any "*-flash-lite" model
                    //     anywhere in this list wins auto-selection outright, exactly
                    //     how gemini-2.0-flash-lite got auto-picked and broke tool calls
                    //     the first time (see V165/V167). Keeping "flash-lite" out of
                    //     every Gemini model id in this catalog is the only way to keep
                    //     hosted-pool auto-selection off a thought_signature-affected
                    //     model - list order alone cannot protect against this keyword.
                    // pickBestModel() then reaches the "flash" keyword and picks the
                    // first list entry containing it - gemini-2.5-flash must stay first
                    // so it wins before gemini-3.5-flash's own "flash" match.
                    Map.entry(
                            ProviderName.GEMINI,
                            List.of(
                                    new SeedModel(
                                            "gemini-2.5-flash",
                                            "Gemini 2.5 Flash",
                                            1_000_000,
                                            true,
                                            true,
                                            true),
                                    new SeedModel(
                                            "gemini-2.5-pro",
                                            "Gemini 2.5 Pro",
                                            1_000_000,
                                            true,
                                            true,
                                            true),
                                    new SeedModel(
                                            "gemini-3.5-flash",
                                            "Gemini 3.5 Flash",
                                            1_000_000,
                                            true,
                                            true,
                                            true))),
                    // Grok line replaced Aug 2026 (see V182): xAI retired grok-3,
                    // grok-3-mini, grok-4 and grok-4-fast on 2026-05-15. grok-3 redirects
                    // to grok-4.3 until ~2026-08-15 and then hard-400s like the others
                    // already do - so every previous id here was either broken or about
                    // to break. RETIRED_MODEL_REPLACEMENTS below maps each old id to its
                    // successor for agents that still have one persisted.
                    Map.entry(
                            ProviderName.GROK,
                            List.of(
                                    // grok-4.6 (docs.x.ai, Sept 2026): current flagship for
                                    // agentic/coding
                                    // work, 500K ctx, text+image input, reasoning
                                    // low/med/high/xhigh.
                                    // grok-4-1-fast-* (x.ai/news/grok-4-1-fast): the
                                    // Agent-Tools-API line -
                                    // 2M ctx, purpose-built for server-side tool calling
                                    // (web_search,
                                    // x_search, code_execution); pairs with
                                    // Agent.xaiServerToolsEnabled.
                                    new SeedModel(
                                            "grok-4.6", "Grok 4.6", 500_000, true, false, false),
                                    new SeedModel(
                                            "grok-4-1-fast-reasoning",
                                            "Grok 4.1 Fast (Reasoning)",
                                            2_000_000,
                                            false,
                                            false,
                                            false),
                                    new SeedModel(
                                            "grok-4-1-fast-non-reasoning",
                                            "Grok 4.1 Fast (Non-Reasoning)",
                                            2_000_000,
                                            false,
                                            false,
                                            false),
                                    new SeedModel(
                                            "grok-4.5", "Grok 4.5", 500_000, true, false, false),
                                    new SeedModel(
                                            "grok-4.3", "Grok 4.3", 1_000_000, true, false, false),
                                    new SeedModel(
                                            "grok-4.20-0309-reasoning",
                                            "Grok 4.20 Reasoning (0309)",
                                            256_000,
                                            false,
                                            false,
                                            false),
                                    new SeedModel(
                                            "grok-4.20-0309-non-reasoning",
                                            "Grok 4.20 Non-Reasoning (0309)",
                                            256_000,
                                            false,
                                            false,
                                            false),
                                    new SeedModel(
                                            "grok-build-0.1",
                                            "Grok Build 0.1",
                                            256_000,
                                            false,
                                            false,
                                            false))),
                    Map.entry(
                            ProviderName.SARVAM,
                            List.of(
                                    // BUG FIX (2026-08-04): both rows shared the id "sarvam-105b" -
                                    // the 30B row's id was a copy-paste of the 105B row below it.
                                    // control_plane.provider_model_catalog has
                                    // UNIQUE(provider_name,
                                    // model_id) (V66), so refreshProvider(SARVAM)'s saveAll() threw
                                    // a
                                    // constraint violation on every run, was swallowed by
                                    // refreshProvider's catch block, and left the catalog
                                    // permanently
                                    // empty - the dropdown never populated, and (for platform-pool
                                    // tenants, where this catalog is a hard allowlist - see
                                    // AgentService#updateModel's javadoc) manual entry was rejected
                                    // too, since findModel() can never find anything in an empty
                                    // catalog. Distinct ids fix both.
                                    // Context windows corrected Aug 2026: 64K (30B) / 128K (105B)
                                    // per
                                    // Sarvam's current model reference - both were wrongly seeded
                                    // 32K.
                                    new SeedModel(
                                            "sarvam-30b",
                                            "Sarvam 30B (Indic)",
                                            64_000,
                                            false,
                                            false,
                                            false),
                                    new SeedModel(
                                            "sarvam-105b",
                                            "Sarvam 105B (Indic)",
                                            128_000,
                                            false,
                                            false,
                                            false))));

    /**
     * Every seeded provider: the hosted-pool providers above plus the July 2026 fan-out in {@link
     * ProviderFanOutSeedModels}. Split across two classes only to keep each file under the
     * repository's file-size ratchet; a provider may appear in exactly one of them.
     */
    private static final Map<ProviderName, List<SeedModel>> SEED_MODELS =
            merge(HOSTED_POOL_SEEDS, ProviderFanOutSeedModels.MODELS);

    private static Map<ProviderName, List<SeedModel>> merge(
            Map<ProviderName, List<SeedModel>> first, Map<ProviderName, List<SeedModel>> second) {
        Map<ProviderName, List<SeedModel>> all = new LinkedHashMap<>(first);
        second.forEach(
                (provider, models) -> {
                    if (all.putIfAbsent(provider, models) != null) {
                        throw new IllegalStateException("Provider seeded twice: " + provider);
                    }
                });
        return Collections.unmodifiableMap(all);
    }

    record SeedModel(
            String modelId,
            String displayName,
            Integer contextWindow,
            boolean supportsImageInput,
            boolean supportsAudioInput,
            boolean supportsVideoInput) {}

    /**
     * The seeded providers, in the order {@link ProviderModelCatalogService#refreshAll} visits
     * them.
     */
    static Set<ProviderName> providers() {
        return SEED_MODELS.keySet();
    }

    /** Catalog rows for {@code provider}'s seed list; empty for an unseeded provider. */
    static List<ProviderModelCatalogEntry> entriesFor(ProviderName provider) {
        List<SeedModel> seeds = SEED_MODELS.getOrDefault(provider, List.of());
        return seeds.stream()
                .map(
                        s ->
                                new ProviderModelCatalogEntry(
                                        provider,
                                        s.modelId(),
                                        s.displayName(),
                                        s.contextWindow(),
                                        s.supportsImageInput(),
                                        s.supportsAudioInput(),
                                        s.supportsVideoInput()))
                .toList();
    }
}
