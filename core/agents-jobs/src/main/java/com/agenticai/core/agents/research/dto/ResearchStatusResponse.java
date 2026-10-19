package com.agenticai.core.agents.research.dto;

import java.math.BigDecimal;

/**
 * {@code GET /api/research/status} - everything the page needs to decide between "not available",
 * "needs setup" and "ready", and to print the limits honestly.
 *
 * @param enabled          {@code app.research.deep.enabled}: the deep_research tool exists here
 * @param searchConfigured an active platform web-search key exists
 * @param runsEnabled      {@code app.research.runs.enabled}: the run endpoints accept requests
 * @param limits           per-run ceilings and the per-user daily cap
 * @param remainingToday   runs the caller may still start in the current 24h window; {@code null} when
 *                         no daily cap is configured ({@code limits.maxRunsPerUserPerDay == 0})
 */
public record ResearchStatusResponse(
    boolean enabled,
    boolean searchConfigured,
    boolean runsEnabled,
    Limits limits,
    Integer remainingToday
) {

    /**
     * @param maxSearchesPerRun      search queries across every deep_research call in one run
     * @param maxFetchesPerRun       page-read attempts across every deep_research call in one run
     * @param maxSources             pages read per deep_research call
     * @param totalDeadlineSeconds   wall-clock cap for ONE deep_research call (a run may make more than one)
     * @param runBudgetUsd           the per-run USD budget gate; 0 = not configured
     * @param maxRunsPerUserPerDay   0 = no daily cap
     * @param runTimeoutSeconds      timeout requested for the whole run
     */
    public record Limits(
        int maxSearchesPerRun,
        int maxFetchesPerRun,
        int maxSources,
        int totalDeadlineSeconds,
        BigDecimal runBudgetUsd,
        int maxRunsPerUserPerDay,
        int runTimeoutSeconds
    ) {
    }
}
