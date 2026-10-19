package com.agenticai.core.agents.tools.web;

import java.util.List;

/**
 * One web-search backend behind the {@code web_search} tool and {@code deep_research}. Adapters
 * exist for Brave (the original, unchanged behaviour), SearXNG (self-hosted, free to run), Tavily,
 * Exa and Serper; {@link WebSearchProviderRegistry} picks one and degrades to an ordered fallback
 * list.
 *
 * <p>Contract, deliberately the one {@link BraveWebSearchClient} already had so every provider
 * reads the same to the tool callback:
 *
 * <ul>
 *   <li>Results use the existing {@link BraveWebSearchClient.SearchResult} shape (title, url,
 *       snippet, optional date).
 *   <li>{@link #search} throws {@link WebSearchException} - whose message is already worded for a
 *       person - for every failure mode (not configured, key rejected, rate limit, quota used up,
 *       outage, bad response). It never throws anything else for a provider-side failure, and never
 *       echoes an upstream body or a key.
 *   <li>An empty result list is NOT an error (the tool says "no results"); it does not trigger a
 *       fallback.
 * </ul>
 */
public interface WebSearchProvider {

    /**
     * Stable lower-case id used in configuration and status (e.g. {@code brave}, {@code searxng}).
     */
    String id();

    /** Human label for notes and the admin status view (e.g. {@code Brave Search}). */
    String label();

    /**
     * Whether a call could be attempted: the key/base URL it needs is present. Cheap and
     * non-throwing; it does NOT prove the credential works.
     */
    boolean configured();

    /**
     * The person-readable sentence for "this provider is not set up", naming what an operator must
     * add.
     */
    String notConfiguredMessage();

    /**
     * Runs one search.
     *
     * @param count requested result count; null = the provider default (5), clamped to 1..10
     * @throws WebSearchException with a person-readable reason on every failure
     */
    List<BraveWebSearchClient.SearchResult> search(String query, Integer count);
}
