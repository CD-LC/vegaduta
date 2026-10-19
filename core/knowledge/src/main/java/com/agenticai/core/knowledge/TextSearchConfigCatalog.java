package com.agenticai.core.knowledge;

import java.util.List;

/**
 * The fixed set of Postgres text-search configurations a knowledge
 * collection's keyword (tsvector/tsquery) side can be created against -
 * RAG audit fix (2026-07-29) replacing migration V87's hardcoded 'english',
 * which drove keyword recall on non-English (especially Indic) content to
 * effectively zero: 'english' stemming/stopwords actively mangle Hindi or
 * Tamil text rather than merely ignoring it.
 *
 * <p>Deliberately a static Java list, same posture as {@link
 * EmbeddingModelCatalog}: these keys are interpolated (parameterized, then
 * cast {@code ?::regconfig}) into SQL by {@code KnowledgeIngestionService}
 * and validated here at collection creation, so an unknown value fails
 * loudly at creation time instead of breaking every subsequent ingest.
 * Every entry ships with stock PostgreSQL 13+ (this platform deploys on a
 * far newer pgvector image) - no CREATE TEXT SEARCH CONFIGURATION needed.
 *
 * <p>{@link #DEFAULT_KEY} is {@code simple} - lowercase/split only, no
 * language-specific stemming or stopwords - because it is the only config
 * that treats every language neutrally, which is the right default for a
 * platform whose collections routinely hold mixed English + Indic content.
 * 'english' remains available (and is what all pre-V154 collections were
 * backfilled to, preserving their exact existing behavior - see that
 * migration's header). Postgres has no Indic-language stemmer configs
 * beyond the Snowball 'hindi'/'tamil'/'nepali' entries listed here; for
 * everything else Indic, 'simple' IS the correct choice, not a fallback.
 *
 * <p>Like {@code embeddingModel}, a collection's config is chosen once at
 * creation and never changed - every stored chunk's tsvector was built with
 * it, and changing it later would make new chunks' vectors inconsistent
 * with old ones (see {@link KnowledgeCollection}'s javadoc).
 */
public final class TextSearchConfigCatalog {

    public static final String DEFAULT_KEY = "simple";

    public record ConfigOption(String key, String label, boolean isDefault) {}

    private static final List<ConfigOption> CONFIGS = List.of(
        new ConfigOption("simple", "Language-agnostic (recommended for Indic/mixed content)", true),
        new ConfigOption("english", "English (stemming + stopwords)", false),
        new ConfigOption("hindi", "Hindi", false),
        new ConfigOption("tamil", "Tamil", false),
        new ConfigOption("nepali", "Nepali", false),
        new ConfigOption("arabic", "Arabic", false),
        new ConfigOption("danish", "Danish", false),
        new ConfigOption("dutch", "Dutch", false),
        new ConfigOption("finnish", "Finnish", false),
        new ConfigOption("french", "French", false),
        new ConfigOption("german", "German", false),
        new ConfigOption("greek", "Greek", false),
        new ConfigOption("hungarian", "Hungarian", false),
        new ConfigOption("indonesian", "Indonesian", false),
        new ConfigOption("irish", "Irish", false),
        new ConfigOption("italian", "Italian", false),
        new ConfigOption("lithuanian", "Lithuanian", false),
        new ConfigOption("norwegian", "Norwegian", false),
        new ConfigOption("portuguese", "Portuguese", false),
        new ConfigOption("romanian", "Romanian", false),
        new ConfigOption("russian", "Russian", false),
        new ConfigOption("spanish", "Spanish", false),
        new ConfigOption("swedish", "Swedish", false),
        new ConfigOption("turkish", "Turkish", false)
    );

    private TextSearchConfigCatalog() {
    }

    public static List<ConfigOption> all() {
        return CONFIGS;
    }

    public static boolean isValid(String key) {
        return CONFIGS.stream().anyMatch(c -> c.key().equals(key));
    }
}
