package com.agenticai.core.providers;

import java.util.Optional;

/**
 * Port: loads a local model into memory ahead of the first chat. The local-models package depends
 * on this package (pool rows, probes), so this package must not depend back on it; the Ollama
 * gateway in {@code localmodels} implements this.
 */
public interface LocalModelWarmer {

    /**
     * Loads {@code tag} now and blocks for the load; never throws.
     *
     * @return the load time in milliseconds, or empty when the server is not reachable, refused, or
     *     does not know the tag
     */
    Optional<Long> warm(String tag);
}
