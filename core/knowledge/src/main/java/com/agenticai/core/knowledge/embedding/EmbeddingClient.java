package com.agenticai.core.knowledge.embedding;

import java.util.List;

/**
 * Pluggable text-embedding backend for {@link com.agenticai.core.knowledge.SidecarClient}'s
 * {@code embed*} methods.
 *
 * <p>Default (no bean of this type): embeddings are computed by the Python
 * sidecar's {@code /embed} endpoint, exactly as before this interface
 * existed. A bean of this type exists ONLY when explicitly opted in via
 * {@code app.embeddings.provider} (see {@link EmbeddingProperties}); when it
 * does, {@code SidecarClient} routes every embedding call to it instead of
 * the sidecar, so callers (knowledge ingestion/search/re-embed, agent and
 * tenant memory, semantic response cache, tool relevance) are untouched.
 *
 * <p>Implementations return the model's native vectors, L2-normalized; the
 * caller ({@code SidecarClient}) applies the same zero-padding rules the
 * sidecar applies (see {@link EmbeddingProperties} for the column widths).
 */
public interface EmbeddingClient {

    /** Stable provider id for logs/diagnostics, e.g. {@code openai-compatible}. */
    String provider();

    /** Native output width of the configured model; validated against the pgvector columns at startup. */
    int dimensions();

    /**
     * Identifier of the concrete model behind this client (e.g. {@code ollama:nomic-embed-text}),
     * recorded per knowledge collection so a provider or model change is detected instead of
     * silently mixing vectors. Blank for implementations that cannot name it.
     */
    default String modelId() {
        return "";
    }

    /**
     * True when this model's vectors may be cut to a shorter prefix and re-normalised without
     * ruining retrieval (Matryoshka-trained models). Lets a wider-than-column model still fit the
     * fixed {@code vector(768)} / {@code vector(384)} columns; otherwise a too-wide vector is refused.
     */
    default boolean supportsTruncation() {
        return false;
    }

    /**
     * Embeds search queries. Defaults to {@link #embed}; models that use asymmetric query/document
     * prefixes (nomic-embed-text, qwen3-embedding, ...) override it.
     */
    default List<float[]> embedQuery(List<String> texts) {
        return embed(texts);
    }

    /**
     * Embeds each text; the result has the same size and order as {@code texts}.
     *
     * @throws com.agenticai.core.knowledge.SidecarCommunicationException on any
     *     transport/response failure, so existing fail-open callers keep working
     */
    List<float[]> embed(List<String> texts);
}
