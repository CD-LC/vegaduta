package com.agenticai.core.agents.tools.web;

import java.util.List;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The Brave adapter: a pure delegate to the unchanged {@link BraveWebSearchClient} (same endpoint,
 * same circuit breaker name {@code brave}, same sentences). It exists only so Brave can sit in the
 * same ordered chain as the other providers; with {@code app.web-search.provider=brave} and no
 * fallback the chain is never used at all and the tool keeps calling the client directly.
 */
public class BraveWebSearchProvider implements WebSearchProvider {

    private static final Logger log = LoggerFactory.getLogger(BraveWebSearchProvider.class);
    public static final String PROVIDER_ID = "brave";

    private final BraveWebSearchClient client;

    /** Resolved per call, like the tool callback does - rotation takes effect on the next call. */
    private final Supplier<String> keySupplier;

    public BraveWebSearchProvider(BraveWebSearchClient client, Supplier<String> keySupplier) {
        this.client = client;
        this.keySupplier = keySupplier;
    }

    @Override
    public String id() {
        return PROVIDER_ID;
    }

    @Override
    public String label() {
        return "Brave Search";
    }

    @Override
    public boolean configured() {
        try {
            String key = keySupplier == null ? null : keySupplier.get();
            return key != null && !key.isBlank();
        } catch (RuntimeException e) {
            log.warn("Brave Search credential could not be read", e);
            return false;
        }
    }

    @Override
    public String notConfiguredMessage() {
        return "web search is not configured on this platform yet. A platform admin needs to add a "
                + "Brave Search API key under Tool Credentials.";
    }

    @Override
    public List<BraveWebSearchClient.SearchResult> search(String query, Integer count) {
        return client.search(query, count, keySupplier == null ? null : keySupplier.get());
    }
}
