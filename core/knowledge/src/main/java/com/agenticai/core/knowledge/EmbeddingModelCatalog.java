package com.agenticai.core.knowledge;

import java.util.List;

/**
 * The fixed set of local embedding models a knowledge collection can be
 * created against (Knowledge multi-model ingestion). Deliberately a static
 * Java list, not DB-backed or sidecar-queried at runtime - these are exactly
 * the models {@code sidecar/app/embeddings.py}'s {@code MODEL_REGISTRY}
 * knows how to load, so the two lists must be changed together; keeping this
 * one canonical and small avoids the two ever drifting silently out of sync
 * (a request naming a model neither side recognizes fails loudly instead).
 *
 * <p>All five stay local/CPU (sentence-transformers), never a paid hosted
 * embeddings API - same "usable before any LLM provider is even activated"
 * reasoning as the original single-model choice (see {@code embeddings.py}'s
 * module docstring). {@code nativeDimensions} is what the model itself
 * outputs; every vector is zero-padded up to {@link #STORAGE_DIMENSIONS} by
 * the sidecar before it's ever sent back to Java, so one {@code vector(768)}
 * pgvector column serves every model here without a per-model table or
 * column. Zero-padding preserves cosine similarity exactly (it changes
 * neither a vector's direction nor its norm) - see migration
 * V26__knowledge_multi_model.sql's header comment for the same argument
 * applied to the historical 384-dim data it backfills.
 *
 * <p>A collection's model is chosen at creation and changes only through a
 * re-embed job ({@code KnowledgeReembedService}), which builds the new
 * model's vectors in shadow storage and swaps them in with the model key in
 * one transaction - mixing chunks embedded by two different models as one
 * collection's live set would make its own internal search results
 * incomparable to each other, not just slower. The keys here are also
 * interpolated (after a {@code [a-z0-9-]} check) into the per-model partial
 * HNSW index DDL in {@code VectorSchemaInitializer}, so keep them to that
 * alphabet.
 */
public final class EmbeddingModelCatalog {

    /** The width every stored vector is padded to, regardless of source model - see class javadoc. */
    public static final int STORAGE_DIMENSIONS = 768;

    public static final String DEFAULT_KEY = "minilm-l6-v2";

    public record ModelOption(String key, String label, int nativeDimensions, String description, boolean isDefault) {}

    private static final List<ModelOption> MODELS = List.of(
        new ModelOption(
            "minilm-l6-v2", "MiniLM-L6 (fast, default)", 384,
            "Smallest and fastest of the four - good default for most knowledge bases, especially larger ones where ingestion speed matters.",
            true
        ),
        new ModelOption(
            "mpnet-base-v2", "MPNet-base (highest quality)", 768,
            "Larger and slower, generally the most accurate of the four for semantic search - worth it for a smaller, high-value collection (e.g. legal/policy documents) where retrieval quality matters more than ingestion speed.",
            false
        ),
        new ModelOption(
            "bge-small-en", "BGE-small (retrieval-tuned)", 384,
            "Similar speed to the default, tuned specifically for retrieval/search tasks rather than general sentence similarity - a reasonable alternative default for FAQ/support-style collections.",
            false
        ),
        new ModelOption(
            "multi-qa-minilm", "Multi-QA MiniLM (Q&A-tuned)", 384,
            "Trained specifically on question-answer pairs - worth trying if your collection is mostly Q&A-style content and agents mostly query it with questions.",
            false
        ),
        // RAG audit fix (2026-07-29): the four models above are all
        // English-trained, which contradicted this platform's Indic-language
        // positioning - semantic recall on Hindi/Tamil/etc. content was
        // effectively an accident of tokenizer overlap. This model
        // (sentence-transformers' paraphrase-multilingual-MiniLM-L12-v2)
        // covers 50+ languages including the major Indic ones, outputs 384
        // native dims (fits the existing zero-padding-to-768 scheme
        // unchanged, unlike bge-m3's 1024 dims which would force a column
        // widening + full re-embed), and stays CPU-friendly (~470MB).
        // Deliberately NOT the default: changing DEFAULT_KEY would only
        // affect new collections but silently downgrade pure-English ones
        // (the multilingual model trades some English quality for breadth);
        // existing collections are untouched either way, since a
        // collection's model is fixed at creation (see class javadoc).
        new ModelOption(
            "multilingual-minilm", "Multilingual MiniLM (Indic + 50 languages)", 384,
            "Supports 50+ languages including Hindi, Tamil, Telugu, Bengali, Marathi and other Indic languages - choose this for any collection holding non-English or mixed-language documents; the other four models are English-trained.",
            false
        )
    );

    private EmbeddingModelCatalog() {
    }

    public static List<ModelOption> all() {
        return MODELS;
    }

    public static boolean isValid(String key) {
        return MODELS.stream().anyMatch(m -> m.key().equals(key));
    }
}
