package com.agenticai.core.workflow.dto;

import com.agenticai.core.agents.dto.ChatInlineAttachment;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

/**
 * All fields optional: {@code input} seeds the Trigger node's output
 * (defaults to a friendly "manually started" message so a bare Trigger ->
 * Agent -> Output graph is still runnable with zero setup); {@code
 * requestedBy} is stamped on the run and on any HITL approval request it
 * creates, same "who initiated this" convention {@code
 * HumanApprovalGateService#evaluate} already uses everywhere else.
 *
 * <p>{@code clientRunId} (real-time log viewer, #393): by default {@code
 * /run} is one synchronous, blocking HTTP call end to end. What makes live
 * polling possible anyway is that the run's row is persisted immediately,
 * before the first node executes, and its trace is updated incrementally
 * after every node (see {@code WorkflowExecutionEngine#execute}) - so a
 * caller that already knows the run's id can poll {@code
 * GET /{workflowId}/runs/{runId}} concurrently with its still-open {@code
 * POST /run} call and see it fill in live. That only works if the caller
 * supplies the id up front, since the server would otherwise only reveal it
 * in the final response once the whole run has already finished. If
 * omitted, the server generates one as before (e.g. the webhook/schedule
 * triggers, which never poll for progress).
 *
 * <p>{@code background} (2026-08-17): {@code true} makes {@code /run}
 * return as soon as the run row is validated and persisted (status
 * RUNNING), with the node traversal continuing on a server-side worker pool
 * - see {@code WorkflowBackgroundRunService}. The caller then polls {@code
 * GET /{workflowId}/runs/{runId}} to completion (the response carries the
 * run id even when {@code clientRunId} was omitted). This is the mode for
 * runs that outlive proxy timeouts - Cloudflare 524s any request older than
 * ~100s, which a multi-minute agent workflow trivially is. Omitted/false
 * keeps the fully synchronous contract above, which webhook triggers and
 * MCP {@code call_workflow} rely on for their "response carries the final
 * output" semantics.
 *
 * <p>{@code attachments} (Workflow Share mic/video): an optional inline
 * image, same {@link ChatInlineAttachment} type {@code
 * AgentChatService#chat} already accepts for a direct agent chat turn (e.g.
 * a Workflow Share visitor's camera snapshot). Purely additive - omitted
 * (null/empty) reproduces today's text-only behavior exactly. Consumed by
 * AT MOST ONE node: whichever node the graph executes immediately after its
 * Trigger node (the only node whose input is genuinely this run's raw,
 * unprocessed payload) - see {@code WorkflowExecutionEngine#runTraversal}'s
 * {@code pendingAttachments} for where that hand-off happens, and its
 * javadoc for the documented degradations (Temporal execution engine,
 * approval-gated entry node, Loop/Parallel as the entry node).
 *
 * <p>{@code mode} (draft runs and data pinning): {@code "draft"} runs the live editable graph and uses pinned
 * node data; absent, blank or {@code "production"} is the PRODUCTION snapshot, which never reads a pin - so
 * every existing client is unaffected. Only the canvas Run button sends {@code "draft"}. Any other value is a
 * 400. Webhook, cron, channel, event, MCP, share-link and sub-workflow runs do not come through this request at
 * all and are always production.
 *
 * <p>{@code simulateSends} (dry run): {@code true} together with {@code mode: "draft"} runs the draft with its
 * outbound sends simulated - output nodes on an external channel, bulk email and non-GET HTTP report what they
 * would send and to whom instead of sending it (see {@code WorkflowDryRun} for the exact list, and for what is
 * NOT covered). Approval nodes still pause. Absent or false is an ordinary run. {@code true} without draft mode,
 * or while {@code app.workflow.draft-run.simulate-sends.enabled} is off, is refused (400 / 409) rather than
 * silently run for real.
 *
 * <p>{@code comment}: an optional operator note ("Run comment" in the Run modal). It is stored on the run and
 * returned in run history; it is never appended to {@code input}, so nodes and branches do not see it. At most
 * 2000 characters.
 */
public record WorkflowRunRequest(
    String input,
    String requestedBy,
    UUID clientRunId,
    List<ChatInlineAttachment> attachments,
    Boolean background,
    String mode,
    Boolean simulateSends,
    @Size(max = 2000, message = "comment must be at most 2000 characters") String comment
) {

    /** Pre-dry-run arity: an ordinary run. */
    public WorkflowRunRequest(
        String input, String requestedBy, UUID clientRunId, List<ChatInlineAttachment> attachments,
        Boolean background, String mode
    ) {
        this(input, requestedBy, clientRunId, attachments, background, mode, null, null);
    }

    /** Pre-comment arity: a run with no comment. */
    public WorkflowRunRequest(
        String input, String requestedBy, UUID clientRunId, List<ChatInlineAttachment> attachments,
        Boolean background, String mode, Boolean simulateSends
    ) {
        this(input, requestedBy, clientRunId, attachments, background, mode, simulateSends, null);
    }
}
