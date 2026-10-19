package com.agenticai.core.knowledge.connector;

import java.util.List;
import java.util.Map;

/**
 * One provider's fetch logic for the live-connector sync path. The
 * {@code config} map is the connector row's decrypted view of its
 * {@code config_jsonb} (secrets already decrypted by the service - a
 * fetcher never sees ciphertext and never persists anything itself).
 */
public interface ConnectorFetcher {

    ConnectorType getType();

    /**
     * Fetches the connector's current document set.
     *
     * @param config decrypted provider config (keys are per-type, documented
     *               on each implementation)
     * @param cursor the connector's last sync cursor, or null for a full
     *               fetch - providers that support incremental sync use it,
     *               the rest ignore it
     * @return the documents to (re)ingest
     * @throws ConnectorFetchException on provider errors (auth, network,
     *         rate limit) - the sync marks the connector failed with this
     *         message rather than dying mid-run
     */
    List<ConnectorDocument> fetch(Map<String, String> config, String cursor);
}
