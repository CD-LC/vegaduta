package com.agenticai.core.knowledge.embedding;

import java.util.Optional;

/**
 * Port: "is there an Ollama this install has registered?" - answered at run time, not at start-up.
 *
 * <p>The console's local-model wizard (and the platform pool it writes) live in the database, which the
 * start-up condition behind {@code app.embeddings.provider=auto} cannot read. Implemented by the
 * providers module, which owns the pool; absent in slim contexts, where {@code auto} keeps its
 * environment-only behaviour.
 */
public interface RegisteredOllamaLookup {

    /** The Ollama root URL (no {@code /v1}) of the model server a platform-pool row points at, if any. */
    Optional<String> baseUrl();
}
