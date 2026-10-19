package com.agenticai.core.workflow;

/**
 * AWAITING_APPROVAL is no longer terminal (the Phase 1 caveat that used to
 * live here is closed): an Approval node still suspends the run and creates
 * a real entry in the existing HITL approvals inbox, but deciding that
 * request now genuinely moves the run on - approval atomically flips it
 * AWAITING_APPROVAL &rarr; RUNNING and then either signals the parked
 * Temporal workflow (signal-resume path: the durable execution itself
 * wakes and re-executes the approved node) or, for runs with no live
 * Temporal execution to signal, re-enters the legacy engine's traversal
 * at the approved node in-process ({@code WorkflowApprovalActionExecutor}
 * picks, see its javadoc). Rejection flips the run to FAILED with the
 * decider and reason recorded. Honest remainder of the old caveat:
 * <ul>
 *   <li>Runs recorded before the Temporal signal-resume change still
 *       resume on the LEGACY engine in-process - their Temporal
 *       executions ended at the halt, so there is nothing parked to
 *       signal (see {@code TemporalWorkflowExecutionEngine}'s javadoc).</li>
 *   <li>Requests recorded before resume support (no resume envelope in
 *       their payload) still cannot re-enter the graph - the decision is
 *       recorded, the run stays AWAITING_APPROVAL, and the request's
 *       execution failure says so explicitly.</li>
 * </ul>
 */
public enum WorkflowRunStatus {
    RUNNING,
    COMPLETED,
    FAILED,
    AWAITING_APPROVAL,
    // Stop Run - a user explicitly requested cancellation via POST
    // .../runs/{runId}/cancel while the run was still RUNNING. On the
    // legacy engine it is checked BETWEEN node executions only
    // (WorkflowExecutionEngine#execute polls WorkflowRunCancellationService
    // at the top of its traversal loop); on the Temporal path the workflow
    // unblocks promptly (TRY_CANCEL) AND the in-flight node activity is
    // interrupted mid-execution - WorkflowNodeActivitiesImpl heartbeats
    // while the node runs, observes the cancellation on a heartbeat, and
    // cancels the node thread. Interruption is still best-effort (a
    // blocked native call ignores the interrupt), so this stays a
    // documented prompt-stop, not a claimed instant-stop guarantee.
    CANCELLED,

    // Pause Run (V261) - an operator paused a RUNNING run via POST
    // .../runs/{runId}/pause. Like CANCELLED, the park is observed BETWEEN
    // node executions only: WorkflowExecutionEngine polls the Redis pause
    // flag at the same checkpoint it polls the cancel flag, then writes the
    // resume envelope (paused_node_id + paused_node_input, V261 columns) so
    // POST .../runs/{runId}/resume can re-enter the traversal at exactly the
    // node that was about to run, with the input it was about to receive -
    // the same re-entry shape WorkflowApprovalActionExecutor uses for
    // approval resumes. PAUSED is NOT terminal: resume flips it back to
    // RUNNING, cancel flips it to CANCELLED (a parked run has no in-flight
    // node to interrupt, so cancelling it is a plain status update). Honest
    // limit, same as approval-resume's: resume re-enters on the LEGACY
    // engine in-process even for runs that started on Temporal - see
    // WorkflowApprovalActionExecutor's javadoc.
    PAUSED,

    // Human-input node (Tier-3, V271) - a "human_input" node suspended the
    // run while it waits for a structured form submission, recorded as a
    // PENDING workflow_input_request row. POST
    // /api/workflows/input-requests/{id}/submit validates the values against
    // the halt-time schema snapshot, flips the run back to RUNNING via the
    // same compareAndSetStatus CAS approval-resume uses, and re-enters
    // WorkflowExecutionEngine#resume at the node under an
    // ApprovalReplayGrant for "workflow.node.human_input" - the re-executed
    // node then returns {"form": {...}, "input": <upstream>} as its output
    // instead of re-halting. Like AWAITING_APPROVAL this is NOT terminal:
    // only submit (or cancel) moves it.
    AWAITING_INPUT,

    // Voice Menu node (V308, "call-logic/DTMF" fast-follow) - a "voice_menu"
    // node suspended a live-call run while it waits for the caller to press
    // digits on their keypad, recorded as a PENDING
    // workflow_voice_menu_request row (see that package's javadoc). Same
    // halt/resume shape as AWAITING_INPUT immediately above - the Twilio
    // DTMF-collect webhook resolves the pending request, flips this run back
    // to RUNNING via the same compareAndSetStatus CAS, and re-enters
    // WorkflowExecutionEngine#resume at the node under an ApprovalReplayGrant
    // for "workflow.node.voice_menu", which returns the pressed digit(s) as
    // this node's branch so the graph's per-digit edges route correctly.
    // Deliberately a SEPARATE status from AWAITING_INPUT rather than reusing
    // it: the human-input console inbox (WorkflowInputRequestController) is
    // keyed off AWAITING_INPUT specifically and has no business surfacing a
    // caller's in-progress IVR menu as something a human needs to fill in.
    // Same honest Temporal limitation as AWAITING_INPUT/AWAITING_APPROVAL's
    // pre-signal-resume behavior: WorkflowRunWorkflowImpl has no
    // voice-menu-specific parking branch, so a Temporal-executed run halts
    // AWAITING_VOICE_INPUT the same terminal-shaped way any unrecognized
    // halt status already does, and resume happens on the legacy engine
    // in-process - acceptable because CALL-triggered runs only ever use the
    // synchronous default engine today (see TwilioVoiceWebhookController).
    AWAITING_VOICE_INPUT
}
