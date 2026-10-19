package com.agenticai.core.providers;

/**
 * One dropdown entry - see {@link ProviderModelCatalogEntry}'s javadoc.
 *
 * <p>The three {@code supports*Input} flags are what the chat/playground
 * upload control reads to decide which attach buttons (if any) to show for
 * the agent's currently-selected model - see that entry's javadoc for why
 * this exists.
 *
 * <p>{@code capabilitySummary} is the model picker's "what does this model
 * actually do" explainer (direct feedback: the picker showed bare model ids
 * with zero capability info). Two-tier: {@code
 * ModelCapabilitySummaryGenerationService} (core/chat) makes one cheap LLM
 * call per catalog model, once, and caches the result on {@link
 * ProviderModelCatalogEntry#getCapabilitySummary()} (column added by V178);
 * {@link #from} prefers that cached value and only falls back to {@link
 * #summarize}'s DETERMINISTIC template - built purely from fields already on
 * this record, never fabricating anything - for a row the generation job
 * hasn't reached yet (a brand-new model, or one re-seeded by a provider
 * refresh since the summary column is replace-not-merge same as every other
 * column on that table). This is why {@code capabilitySummary} is never
 * null/blank here even though the underlying DB column can be.
 *
 * <p>{@code description}/{@code deprecated}/{@code source} were APPENDED
 * (never reorder - 8 frontend call sites depend on the existing JSON shape,
 * additive only) for the live per-tenant model listing ({@code
 * ProviderModelListingService}): {@code source} says whether this entry came
 * from a live provider API call with the tenant's own key ({@code "LIVE"})
 * or the cached platform catalog ({@code "CATALOG"}); {@code description} is
 * the vendor's own blurb when the live endpoint carries one (Gemini,
 * Mistral, OpenRouter), null otherwise; {@code deprecated} is only non-null
 * when a live source explicitly flags it - all three are null/"CATALOG" on
 * the existing catalog-only path, so pre-existing consumers see no change.
 */
public record ProviderModelResponse(
        String modelId,
        String displayName,
        Integer contextWindow,
        boolean supportsImageInput,
        boolean supportsAudioInput,
        boolean supportsVideoInput,
        String capabilitySummary,
        String description,
        Boolean deprecated,
        String source) {

    public static final String SOURCE_LIVE = "LIVE";
    public static final String SOURCE_CATALOG = "CATALOG";

    /**
     * Pre-live-fetch shape - keeps every existing catalog-path constructor
     * call site (and test fixture) compiling unchanged with the catalog
     * defaults for the appended components.
     */
    public ProviderModelResponse(
            String modelId,
            String displayName,
            Integer contextWindow,
            boolean supportsImageInput,
            boolean supportsAudioInput,
            boolean supportsVideoInput,
            String capabilitySummary) {
        this(modelId, displayName, contextWindow, supportsImageInput, supportsAudioInput,
            supportsVideoInput, capabilitySummary, null, null, SOURCE_CATALOG);
    }

    public static ProviderModelResponse from(ProviderModelCatalogEntry entry) {
        String generated = entry.getCapabilitySummary();
        String summary = (generated != null && !generated.isBlank())
            ? generated
            : summarize(entry.getContextWindow(), entry.isSupportsImageInput(),
                entry.isSupportsAudioInput(), entry.isSupportsVideoInput());
        return new ProviderModelResponse(
            entry.getModelId(),
            entry.getDisplayName(),
            entry.getContextWindow(),
            entry.isSupportsImageInput(),
            entry.isSupportsAudioInput(),
            entry.isSupportsVideoInput(),
            summary
        );
    }

    /**
     * The deterministic capability template, exposed package-private so the
     * live-listing merge ({@code ProviderModelListingService}) can build a
     * never-blank {@code capabilitySummary} for a live model the cached
     * catalog has no row (and therefore no generated blurb) for.
     */
    static String summarize(
        Integer contextWindow, boolean image, boolean audio, boolean video
    ) {
        StringBuilder sb = new StringBuilder("Text in, text out");
        java.util.List<String> extraInputs = new java.util.ArrayList<>();
        if (image) extraInputs.add("images");
        if (audio) extraInputs.add("audio");
        if (video) extraInputs.add("video");
        if (!extraInputs.isEmpty()) {
            sb.append(" - also accepts ").append(String.join(", ", extraInputs)).append(" as input");
        }
        sb.append('.');
        if (contextWindow != null && contextWindow > 0) {
            sb.append(' ').append(formatContextWindow(contextWindow)).append(" context window.");
        }
        return sb.toString();
    }

    private static String formatContextWindow(int tokens) {
        if (tokens >= 1_000_000) {
            return (tokens % 1_000_000 == 0 ? tokens / 1_000_000 : Math.round(tokens / 1_000_000.0)) + "M-token";
        }
        if (tokens >= 1_000) {
            return (tokens % 1_000 == 0 ? tokens / 1_000 : Math.round(tokens / 1_000.0)) + "K-token";
        }
        return tokens + "-token";
    }
}
