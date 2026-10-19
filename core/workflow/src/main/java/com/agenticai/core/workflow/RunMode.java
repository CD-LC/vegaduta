package com.agenticai.core.workflow;

import java.util.Locale;

/**
 * How a workflow run was started, and therefore what it may read.
 *
 * <ul>
 *   <li>{@link #PRODUCTION} - every entry point except the canvas Run button and the step test: webhook, cron,
 *       channel, event, MCP, share link, sub-workflow, agent schedule, background. Executes the PRODUCTION
 *       snapshot and NEVER reads a pin.</li>
 *   <li>{@link #DRAFT} - the canvas "Run" button (request {@code mode: "draft"}). Executes the live editable
 *       graph and honours pins.</li>
 *   <li>{@link #TEST} - a throwaway execution of the live graph that honours pins; no run entry point sets it
 *       today (the step test binds it on its expression scope). Accepted by the engine so evals can use it.</li>
 *   <li>{@link #DRAFT_DRY_RUN} - a DRAFT run whose outbound sends are simulated (request {@code mode: "draft"}
 *       plus {@code simulateSends: true}). Everything DRAFT does, and additionally the nodes listed in
 *       {@code WorkflowDryRun} report what they would send instead of sending it. A mode rather than a separate
 *       flag so it is persisted on the run row with the mode: a run that parks at an approval and resumes hours
 *       later is still a dry run, and can never turn into real sends half way through.</li>
 * </ul>
 *
 * Pins are enforced by this mode inside the engine, not by a client flag, so a stray pin cannot change what a
 * production run does.
 */
public enum RunMode {
    PRODUCTION,
    DRAFT,
    TEST,
    DRAFT_DRY_RUN;

    /** True for the modes in which a pinned node is not executed. */
    public boolean honoursPins() {
        return this != PRODUCTION;
    }

    /** True only for a draft run that must not deliver anything: sends are reported, not performed. */
    public boolean simulatesSends() {
        return this == DRAFT_DRY_RUN;
    }

    /** Lower-case wire form, also what {@code $run.mode} shows. */
    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * Parses the optional {@code mode} of a run request. Absent or blank means production, so existing clients
     * are unaffected; only {@code production} and {@code draft} may be requested (a client cannot ask for TEST).
     *
     * @throws IllegalArgumentException for any other value
     */
    public static RunMode fromRequest(String value) {
        if (value == null || value.isBlank()) {
            return PRODUCTION;
        }
        return switch (value.strip().toLowerCase(Locale.ROOT)) {
            case "production" -> PRODUCTION;
            case "draft" -> DRAFT;
            default -> throw new IllegalArgumentException("Unknown run mode '" + value + "' - use \"draft\" or \"production\".");
        };
    }

    /** Lenient parse for values read back from a scope or a row: anything unknown is production (never honours pins). */
    public static RunMode fromWire(String value) {
        if (value == null) {
            return PRODUCTION;
        }
        return switch (value.strip().toLowerCase(Locale.ROOT)) {
            case "draft" -> DRAFT;
            case "test" -> TEST;
            case "draft_dry_run" -> DRAFT_DRY_RUN;
            default -> PRODUCTION;
        };
    }
}
