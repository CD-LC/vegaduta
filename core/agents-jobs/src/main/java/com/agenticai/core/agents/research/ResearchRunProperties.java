package com.agenticai.core.agents.research;

import java.math.BigDecimal;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Settings for research runs ({@code app.research.runs.*}) plus the read-only copies of the
 * {@code app.research.deep.*} ceilings the status endpoint reports. The deep-research values are bound
 * with the SAME keys and defaults {@code AgentToolProvider} uses, so what the page shows is what the
 * tool enforces.
 */
@Component
public class ResearchRunProperties {

    private final boolean runsEnabled;
    private final int maxPerUserPerDay;
    private final int timeoutSeconds;
    private final boolean deepEnabled;
    private final int maxSearchesPerRun;
    private final int maxFetchesPerRun;
    private final int maxSources;
    private final int totalDeadlineSeconds;
    private final BigDecimal runBudgetUsd;

    public ResearchRunProperties(
        @Value("${app.research.runs.enabled:false}") boolean runsEnabled,
        @Value("${app.research.runs.max-per-user-per-day:5}") int maxPerUserPerDay,
        @Value("${app.research.runs.timeout-seconds:600}") int timeoutSeconds,
        @Value("${app.research.deep.enabled:false}") boolean deepEnabled,
        @Value("${app.research.deep.max-searches-per-run:12}") int maxSearchesPerRun,
        @Value("${app.research.deep.max-fetches-per-run:12}") int maxFetchesPerRun,
        @Value("${app.research.deep.max-sources:8}") int maxSources,
        @Value("${app.research.deep.total-deadline-seconds:120}") int totalDeadlineSeconds,
        @Value("${app.guardrails.run-budget.usd:0}") BigDecimal runBudgetUsd
    ) {
        this.runsEnabled = runsEnabled;
        this.maxPerUserPerDay = Math.max(0, maxPerUserPerDay);
        this.timeoutSeconds = Math.max(60, timeoutSeconds);
        this.deepEnabled = deepEnabled;
        this.maxSearchesPerRun = Math.max(1, maxSearchesPerRun);
        this.maxFetchesPerRun = Math.max(1, maxFetchesPerRun);
        this.maxSources = Math.max(1, maxSources);
        this.totalDeadlineSeconds = Math.max(1, totalDeadlineSeconds);
        this.runBudgetUsd = runBudgetUsd == null ? BigDecimal.ZERO : runBudgetUsd;
    }

    /** {@code app.research.runs.enabled} - the REST facade and its page. Default OFF. */
    public boolean isRunsEnabled() {
        return runsEnabled;
    }

    /** Runs one user may start per day; 0 = no cap. */
    public int getMaxPerUserPerDay() {
        return maxPerUserPerDay;
    }

    /** Job timeout requested for a research run (the job service still clamps it to its own maximum). */
    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    /** {@code app.research.deep.enabled} - whether the deep_research tool exists on this deployment. */
    public boolean isDeepEnabled() {
        return deepEnabled;
    }

    public int getMaxSearchesPerRun() {
        return maxSearchesPerRun;
    }

    public int getMaxFetchesPerRun() {
        return maxFetchesPerRun;
    }

    public int getMaxSources() {
        return maxSources;
    }

    public int getTotalDeadlineSeconds() {
        return totalDeadlineSeconds;
    }

    public BigDecimal getRunBudgetUsd() {
        return runBudgetUsd;
    }
}
