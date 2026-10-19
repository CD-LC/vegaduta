package com.agenticai.core.quality.eval;

/**
 * Master Roadmap 2026 item #15 - one golden-dataset regression case: a
 * system prompt (either a real agent-template persona sourced from
 * {@code web/app/components/agentTemplates.js}, or a purpose-built short
 * persona for a capability dimension the platform actually sells - see the
 * category taxonomy in this module's eval-harness.md) paired with one realistic
 * user turn and a rubric describing what a passing response must do.
 *
 * <p>The harness is deliberately text-in/judge-out only: no live tools, no
 * DB, no channels. Rubrics for capability dimensions like tool selection
 * therefore check that the model <em>describes</em> the right capability,
 * not that it invokes one - see eval-harness.md's "honest to the runner" note.
 *
 * @param id short stable identifier - shown in the report, and is what a
 *     human re-running a single failing case would reference.
 * @param persona which agent template / persona this case exercises
 *     (informational, not used for grading).
 * @param category which taxonomy bucket this case belongs to (see
 *     eval-harness.md for the closed list) - used only to group the summary
 *     report and tag the trace, never for grading. Nullable for backward
 *     compatibility with pre-category dataset entries; {@link
 *     #categoryOrDefault()} is the accessor report code should use.
 * @param systemPrompt the literal system prompt under test.
 * @param userMessage the one user turn sent after {@code systemPrompt}.
 * @param rubric plain-language description of what the judge model should
 *     check for - phrased as a requirement ("must ask before...", "must
 *     refuse to..."), not a yes/no question, so the judge prompt can present
 *     it directly.
 * @param feasibility whether this case is a task the assistant is actually
 *     supposed to complete, or one where declining/escalating is the right
 *     answer - see {@link EvalTaskFeasibility} for why that has to be
 *     stated by the dataset author rather than inferred from the rubric
 *     prose. Nullable for backward compatibility exactly like {@code
 *     category}: every entry written before this field existed is a task
 *     meant to be completed, so an absent value resolves to {@link
 *     EvalTaskFeasibility#FEASIBLE} via {@link #feasibilityOrDefault()} and
 *     no pre-existing case changes its grading. Unlike {@code category}
 *     this field DOES affect grading, which is why the accessor below - not
 *     the raw component - is what scoring code must use.
 */
public record EvalCase(String id, String persona, String category, String systemPrompt, String userMessage,
                       String rubric, EvalTaskFeasibility feasibility) {

    /**
     * Category for reporting/grouping, never null. A dataset entry written
     * before the category field existed (or one that simply omits it)
     * deserializes with {@code category == null} - Jackson leaves an absent
     * record component null rather than failing - and groups here rather
     * than crashing the report.
     */
    public String categoryOrDefault() {
        return category == null || category.isBlank() ? "uncategorized" : category;
    }

    /**
     * Feasibility for grading, never null - {@link
     * EvalTaskFeasibility#FEASIBLE} when the dataset entry omits the field.
     *
     * <p>Defaulting to FEASIBLE rather than rejecting an absent value is the
     * deliberate choice: it is what every case in {@code
     * golden-dataset.json} already is, so the three-way scoring introduced
     * alongside this field is a no-op on the existing suite and any verdict
     * change that shows up in the next nightly run is a real behaviour
     * change, not a reclassification artefact. All scoring code must go
     * through this accessor rather than {@link #feasibility()} so the
     * default is applied in exactly one place.
     */
    public EvalTaskFeasibility feasibilityOrDefault() {
        return feasibility == null ? EvalTaskFeasibility.FEASIBLE : feasibility;
    }
}
