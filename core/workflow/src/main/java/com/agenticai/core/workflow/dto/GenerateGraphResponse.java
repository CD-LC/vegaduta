package com.agenticai.core.workflow.dto;

import java.util.List;

/**
 * AI workflow generator (#392) result. {@code graph} has already passed
 * {@code OrchestratorService#validate} before this is returned - the canvas
 * applies it as-is, the same way it applies a saved workflow's graph, rather
 * than needing its own separate "is this generated graph OK" check.
 *
 * <p>Everything after {@code notes} was added later and is optional for a
 * client: a canvas that only reads {@code graph} and {@code notes} keeps
 * working unchanged.
 *
 * @param graph the generated, already-validated node/edge graph.
 * @param notes plain-language caveats the generator wants the user to see
 *     before relying on this graph as-is - e.g. "no agents exist yet, so
 *     this graph skips the Agent step" or "picked the first knowledge
 *     collection since none was specified." Never used to hide a validation
 *     failure - those still surface as a normal 400 with {@code errors}, the
 *     same as the Validate Workflow button.
 * @param mode {@code "edit"} when the request carried a non-empty current
 *     graph and the result is a modification of it, else {@code "create"}.
 * @param explanation what the graph does, one entry per reachable node in
 *     run order - computed from the graph itself, not written by the model.
 * @param suggestedSchedule a recurrence the instruction implied ("every
 *     Monday"), or null. Never applied by the server: a schedule can only be
 *     switched on for an ACTIVE workflow, through the schedule endpoint.
 * @param requirements what still has to be connected before this runs.
 * @param insertedApprovals approval steps the server added in front of a
 *     node that sends something out; empty when none were needed.
 */
public record GenerateGraphResponse(
    WorkflowGraphDto graph,
    List<String> notes,
    String mode,
    List<ExplanationStep> explanation,
    SuggestedSchedule suggestedSchedule,
    Requirements requirements,
    List<InsertedApproval> insertedApprovals
) {
    public GenerateGraphResponse {
        notes = notes != null ? notes : List.of();
    }

    /** Original two-field form, kept for existing callers; the newer fields are null. */
    public GenerateGraphResponse(WorkflowGraphDto graph, List<String> notes) {
        this(graph, notes, null, null, null, null, null);
    }

    /** {@code type} is the lower-case node type; {@code sentence} is plain language, ready to show. */
    public record ExplanationStep(String nodeId, String label, String type, String sentence) {
    }

    /**
     * @param cron five-field unix cron (minute hour day-of-month month day-of-week), already
     *     checked to parse - the form {@code PUT /api/workflows/{id}/schedule} accepts.
     * @param label short human wording, e.g. "Every Monday 09:00".
     * @param timezone the zone the scheduler evaluates the cron in - always "UTC".
     */
    public record SuggestedSchedule(String cron, String label, String timezone) {
    }

    /**
     * @param secrets vault secret names (UPPER_SNAKE) the graph references as {{secret.NAME}}.
     * @param knowledge short phrases naming documents the workflow would need.
     * @param items actionable hints, each pointing at the console page that resolves it.
     */
    public record Requirements(List<String> secrets, List<String> knowledge, List<RequirementItem> items) {
    }

    /**
     * @param kind one of {@code secret}, {@code knowledge}, {@code agent}, {@code channel},
     *     {@code approval-policy}.
     * @param href console path to fix it (e.g. {@code /secrets}), never an absolute URL.
     * @param nodeId the node this concerns, or null when it concerns the whole workflow.
     */
    public record RequirementItem(String kind, String message, String href, String nodeId) {
    }

    /** {@code beforeNodeId} is the node the approval now precedes; {@code sendNodeId} the send that needed it. */
    public record InsertedApproval(String approvalNodeId, String beforeNodeId, String sendNodeId) {
    }
}
