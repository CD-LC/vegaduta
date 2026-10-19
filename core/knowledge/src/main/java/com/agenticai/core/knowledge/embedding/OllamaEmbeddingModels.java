package com.agenticai.core.knowledge.embedding;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * What is known about the common Ollama embedding models: native width, whether the vector may be
 * cut to a prefix (Matryoshka training) and the query/document prefixes the model was trained with.
 * A convenience table, not a gate: an unlisted model still works (its width is learned from the
 * first response), and every value can be overridden under {@code app.embeddings.ollama.*}.
 */
public final class OllamaEmbeddingModels {

    /** Facts about one model; {@code dimensions} 0 = varies by size, learned from the first response. {@code queryPrefix}/{@code documentPrefix} are never null. */
    public record Spec(int dimensions, boolean matryoshka, String queryPrefix, String documentPrefix) {}

    private static final Map<String, Spec> KNOWN = Map.of(
        "nomic-embed-text", new Spec(768, true, "search_query: ", "search_document: "),
        "mxbai-embed-large", new Spec(1024, true,
            "Represent this sentence for searching relevant passages: ", ""),
        "bge-m3", new Spec(1024, false, "", ""),
        "all-minilm", new Spec(384, false, "", ""),
        "snowflake-arctic-embed", new Spec(1024, false,
            "Represent this sentence for searching relevant passages: ", ""),
        // 0.6b = 1024, 4b = 2560, 8b = 4096 and the untagged name is not the small one: learn the width
        // from the first response (Matryoshka, so any of them still fits the columns).
        "qwen3-embedding", new Spec(0, true,
            "Instruct: Given a web search query, retrieve relevant passages that answer the query\nQuery: ", ""),
        "embeddinggemma", new Spec(768, true, "task: search result | query: ", "title: none | text: "));

    private OllamaEmbeddingModels() {
    }

    /** {@code library/nomic-embed-text:v1.5} and {@code nomic-embed-text:latest} both become {@code nomic-embed-text}. */
    static String family(String model) {
        String name = model == null ? "" : model.trim().toLowerCase(Locale.ROOT);
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        int colon = name.indexOf(':');
        return colon >= 0 ? name.substring(0, colon) : name;
    }

    /** Families whose width depends on the tag (snowflake-arctic-embed:22m is 384, :335m is 1024). */
    private static final java.util.Set<String> WIDTH_BY_TAG = java.util.Set.of("snowflake-arctic-embed");

    public static Optional<Spec> lookup(String model) {
        String family = family(model);
        Spec spec = KNOWN.get(family);
        if (spec != null && WIDTH_BY_TAG.contains(family) && hasSizeTag(model)) {
            return Optional.of(new Spec(0, spec.matryoshka(), spec.queryPrefix(), spec.documentPrefix()));
        }
        return Optional.ofNullable(spec);
    }

    private static boolean hasSizeTag(String model) {
        String name = model == null ? "" : model.trim().toLowerCase(Locale.ROOT);
        int colon = name.indexOf(':');
        return colon >= 0 && !name.substring(colon + 1).equals("latest");
    }
}
