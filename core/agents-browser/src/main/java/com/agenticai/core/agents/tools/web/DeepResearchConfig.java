package com.agenticai.core.agents.tools.web;

/**
 * Bounds for the {@code deep_research} tool. Bound from {@code app.research.deep.*} (see
 * application.yml). Every value is a hard ceiling, not a target: effort scaling picks fewer workers
 * for simpler questions.
 *
 * @param enabled offer the tools at all (default true; the tool still needs a platform search key)
 * @param maxWorkers ceiling on parallel search workers per call
 * @param maxSources ceiling on distinct pages read per call
 * @param fetchChars characters kept per fetched page (also the span-offset space)
 * @param maxFetchesPerRun ceiling on page fetches across ALL calls in one turn
 * @param passagesPerSource quoted spans returned per source
 * @param resultsPerSearch results requested per search worker
 * @param workerTimeoutSeconds wall-clock limit per worker phase
 * @param maxSearchesPerRun ceiling on platform-paid search queries across ALL calls in one turn
 * @param totalDeadlineSeconds overall wall-clock cap for ONE deep_research call; when it passes the
 *     call returns whatever it has, marked {@code partial}
 * @param minQuoteChars shortest quote verify_citations will accept (fragments match anything)
 * @param autoOfferOnIntent offer the tools to agents that did not opt in when the user's message
 *     looks like a research request (false = opt-in via the allow-list only)
 */
public record DeepResearchConfig(
        boolean enabled,
        int maxWorkers,
        int maxSources,
        int fetchChars,
        int maxFetchesPerRun,
        int passagesPerSource,
        int resultsPerSearch,
        int workerTimeoutSeconds,
        int maxSearchesPerRun,
        int totalDeadlineSeconds,
        int minQuoteChars,
        boolean autoOfferOnIntent) {

    /**
     * Back-compat: the nine original bounds with the default deadline, quote minimum and intent
     * offer.
     */
    public DeepResearchConfig(
            boolean enabled,
            int maxWorkers,
            int maxSources,
            int fetchChars,
            int maxFetchesPerRun,
            int passagesPerSource,
            int resultsPerSearch,
            int workerTimeoutSeconds,
            int maxSearchesPerRun) {
        this(
                enabled,
                maxWorkers,
                maxSources,
                fetchChars,
                maxFetchesPerRun,
                passagesPerSource,
                resultsPerSearch,
                workerTimeoutSeconds,
                maxSearchesPerRun,
                120,
                20,
                true);
    }

    public DeepResearchConfig {
        maxWorkers = Math.max(1, maxWorkers);
        maxSources = Math.max(1, maxSources);
        fetchChars = Math.max(500, fetchChars);
        maxFetchesPerRun = Math.max(1, maxFetchesPerRun);
        passagesPerSource = Math.max(1, passagesPerSource);
        resultsPerSearch = Math.min(10, Math.max(1, resultsPerSearch));
        workerTimeoutSeconds = Math.max(5, workerTimeoutSeconds);
        maxSearchesPerRun = Math.max(1, maxSearchesPerRun);
        totalDeadlineSeconds = Math.max(1, totalDeadlineSeconds);
        minQuoteChars = Math.max(1, minQuoteChars);
    }

    /**
     * Shipped defaults: available, modest fan-out, 12 page fetch attempts and 12 searches per turn.
     */
    public static DeepResearchConfig defaults() {
        return new DeepResearchConfig(true, 6, 8, 12000, 12, 2, 6, 45, 12);
    }

    /** Tool not offered. */
    public static DeepResearchConfig disabled() {
        return new DeepResearchConfig(false, 1, 1, 500, 1, 1, 1, 5, 1);
    }
}
