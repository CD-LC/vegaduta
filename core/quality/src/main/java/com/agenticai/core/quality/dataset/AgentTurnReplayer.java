package com.agenticai.core.quality.dataset;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * The seam through which the eval-dataset runner replays a case as a real
 * agent turn.
 *
 * <p>Why a seam exists at all: {@code core/quality}'s pom states its own
 * boundary - "No dependencies on agents (to avoid circular dependency)" -
 * so this module cannot name {@code AgentChatService} at compile time. The
 * interface is the contract and lives here, the consumer; the typed
 * implementation lives in the APP module, the only module allowed to see both
 * sides (the same placement as {@code EvalPromotionGateBridge}). It replaced a
 * reflective bridge: a signature change in the chat service is now a compile
 * error, not a per-run failure discovered in production.
 *
 * <p>Implementations MUST be called with {@code TenantContext} already set
 * on the calling thread (the run worker sets it from the run row, exactly
 * like {@code AgentJobExecution#run}) - the underlying chat pipeline reads
 * it synchronously for the kill-switch check, plan resolution and schema
 * routing.
 *
 * <h2>Replay modes</h2>
 * See {@link ReplayMode}: text only (the model can only answer, the {@link EvalMode#QUICK} run), the full tool
 * pipeline in the eval sandbox (the {@link EvalMode#FULL} run: readers run, anything that could change something is
 * simulated, a gated call is held without notifying anyone), or the same under the sandbox's auto-approval (a gated
 * call is approved and counted). Which one a run uses is the run's own mode; an implementation that cannot honour a
 * mode must fail the replay, never quietly run another one.
 */
public interface AgentTurnReplayer {

    /**
     * Replays one user message as a single agent turn and blocks for the
     * complete reply.
     *
     * @param agentId the agent under test
     * @param sessionId a UNIQUE session id per case (the runner uses
     *     {@code "eval:<runId>:<n>"}) so session memory can never bleed one
     *     case's context into the next - each golden case must be judged
     *     cold, or a passing run would depend on case ordering
     * @param subjectKey the HUMAN requester's subject, snapshotted on the
     *     run row - the replayed turns are metered against this key by
     *     {@code PerUserBudgetService}, so a run can never spend money no
     *     human owns ({@code AgentJobService#submit}'s reasoning)
     * @param userMessage the case's user turn
     * @param executeTools full pipeline with tools when {@code true}
     *     (a {@link EvalMode#FULL} run), text only otherwise
     * @param timeout how long to block for the full reply
     * @return the reply plus the tool calls the turn made (always empty for
     *     text-only replays)
     * @throws Exception any replay failure - the runner records it as an
     *     ERROR case result and moves on (per-case isolation)
     */
    ReplayedTurn replay(
        UUID agentId, String sessionId, String subjectKey, String userMessage,
        boolean executeTools, Duration timeout
    ) throws Exception;

    /**
     * The dataset runner's entry point. The default serves the two modes the
     * six-argument form can express and REFUSES the auto-approving one, so an
     * implementation that predates it can never run a tool-gated eval without a
     * human decision by accident.
     */
    default ReplayedTurn replay(ReplayRequest request) throws Exception {
        if (request.mode().autoApproves()) {
            throw new UnsupportedOperationException(
                getClass().getSimpleName() + " cannot run an eval turn under the sandbox auto-approval.");
        }
        return replay(request.agentId(), request.sessionId(), request.subjectKey(), request.userMessage(),
            request.mode().executesTools(), request.timeout());
    }

    /** How a replayed turn uses the agent's tools. */
    enum ReplayMode {
        /** The tool set is withheld; the model can only produce text. The safe default. */
        TEXT_ONLY(false, false),
        /**
         * The real tool pipeline in the eval sandbox: a call that needs a human holds the turn exactly as in
         * production (without recording a request), and a tool that could change something is simulated.
         */
        TOOLS(true, false),
        /**
         * As {@link #TOOLS}, and a gated call is auto-approved by the eval sandbox (never for the excluded tools;
         * the approved tool is still simulated if it could change something), and the run records that it ran this
         * way. Only for tenants the operator declared non-production.
         */
        TOOLS_AUTO_APPROVE(true, true);

        private final boolean executesTools;
        private final boolean autoApproves;

        ReplayMode(boolean executesTools, boolean autoApproves) {
            this.executesTools = executesTools;
            this.autoApproves = autoApproves;
        }

        public boolean executesTools() {
            return executesTools;
        }

        public boolean autoApproves() {
            return autoApproves;
        }

        /** The mode a run's {@link EvalMode} and its auto-approval snapshot select. */
        public static ReplayMode of(EvalMode mode, boolean autoApproveTools) {
            if (mode == null || !mode.executesTools()) {
                return TEXT_ONLY;
            }
            return autoApproveTools ? TOOLS_AUTO_APPROVE : TOOLS;
        }

        /** The mode two booleans select (kept for callers that still hold the pair). */
        public static ReplayMode of(boolean toolExecutionEnabled, boolean autoApproveTools) {
            return of(toolExecutionEnabled ? EvalMode.FULL : EvalMode.QUICK, autoApproveTools);
        }
    }

    /** One replay to run. */
    record ReplayRequest(
        UUID agentId, String sessionId, String subjectKey, String userMessage, ReplayMode mode, Duration timeout
    ) {
    }

    /**
     * How a tool call ended, as the turn itself recorded it ({@code UNKNOWN}: no record, judge by wording).
     * {@code SIMULATED}: the eval sandbox answered with a simulated result instead of running the tool because it
     * could change data or reach outside the platform; the agent's call was made, the effect was not.
     */
    enum ToolStatus {
        OK, FAILED, NOT_RUN, AWAITING_APPROVAL, REPLAYED, SIMULATED, UNKNOWN
    }

    /** One tool invocation observed during a replayed turn. */
    record ReplayedToolCall(String toolName, String input, String output, ToolStatus status) {

        /** A call with no recorded status (the wording of its output is then the only signal). */
        public ReplayedToolCall(String toolName, String input, String output) {
            this(toolName, input, output, ToolStatus.UNKNOWN);
        }

        public ReplayedToolCall {
            status = status == null ? ToolStatus.UNKNOWN : status;
        }
    }

    /**
     * The observable outcome of one replayed turn.
     *
     * @param autoApprovedCalls how many gated calls the sandbox auto-approval let through (0 in the other modes)
     */
    record ReplayedTurn(String responseText, List<ReplayedToolCall> toolCalls, int autoApprovedCalls) {

        public ReplayedTurn(String responseText, List<ReplayedToolCall> toolCalls) {
            this(responseText, toolCalls, 0);
        }
    }
}
