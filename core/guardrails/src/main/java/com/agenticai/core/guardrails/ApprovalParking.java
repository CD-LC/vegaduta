package com.agenticai.core.guardrails;

import com.agenticai.core.approval.ApprovalRequest;
import com.agenticai.core.approval.HumanApprovalGateService;
import com.agenticai.core.approval.ToolCallOrigin;
import com.agenticai.core.prompts.PromptResolver;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;

/**
 * Holds a tool call for human approval on behalf of one {@link GuardedToolCallback}: records the
 * pending request (with the replay envelope, including the turn's scoped memory id), marks the turn
 * parked, and produces the tool result the model reads. Also records the calls the model makes
 * AFTER the turn parked, so they are queued behind the approval instead of being silently lost.
 *
 * <p>Ordering is the point of this class. The turn is marked parked only after the request is
 * durably recorded: a failed record used to leave the turn "parked" with nothing for a human to
 * approve, every later tool answering "waiting for approval" forever. Now a failed record returns
 * an error result to the model, parks nothing, and the turn carries on.
 */
final class ApprovalParking {

    private static final Logger log = LoggerFactory.getLogger(ApprovalParking.class);

    /** The tool result for the model, and whether a pending approval now exists for it. */
    record Outcome(String modelText, boolean recorded) {}

    private final ToolCallback delegate;
    private final HumanApprovalGateService gate;
    private final UUID agentId;
    private final String sessionId;
    private final TurnApprovalState state;
    private final ApprovalParkProperties texts;
    private final PromptResolver prompts;

    ApprovalParking(
        ToolCallback delegate, HumanApprovalGateService gate, UUID agentId, String sessionId,
        TurnApprovalState state, ApprovalParkProperties texts, PromptResolver prompts
    ) {
        this.delegate = delegate;
        this.gate = gate;
        this.agentId = agentId;
        this.sessionId = sessionId;
        this.state = state;
        this.texts = texts;
        this.prompts = prompts;
    }

    /**
     * The tool result text for {@code key}: the prompt registry's (any scope's override wins), else the
     * {@code app.guardrails.approval-park.*} property when an operator changed it, else the shipped wording.
     */
    private String text(String key, String configured, String... nameValuePairs) {
        return GuardrailPrompts.textWithConfiguredAlias(prompts, key, agentId, configured, nameValuePairs);
    }

    /**
     * The id a held call carries in an isolated eval turn, where no approval request is recorded. It is not a UUID
     * on purpose: nothing links it to a request row, an approver's inbox or a notification, because none exists.
     */
    private static final String SANDBOX_REQUEST_PREFIX = "eval-sandbox-";

    /** The gated call itself: record it, then (only then) park the turn. */
    Outcome park(String actionType, String toolName, String toolInput, String taintReason) {
        SandboxApprovalGrants.Grant isolation = state.sandboxGrant();
        if (isolation != null && isolation.isolated()) {
            return holdInSandbox(isolation, toolName, toolInput);
        }
        String summary = approvalSummaryFor(toolName, toolInput);
        if (taintReason != null) {
            // The reviewer must see WHY an otherwise-ungated tool is waiting on them.
            summary = summary + " [flagged: " + taintReason + "]";
        }
        ApprovalRequest request = record(actionType, toolName, toolInput, summary);
        if (request == null) {
            return failed(toolName);
        }
        state.markParked(toolName, TurnApprovalState.callKey(toolName, toolInput), String.valueOf(request.getId()));
        return new Outcome(
            text(GuardrailPrompts.PARK_AWAITING, texts.getAwaitingMessage(), "tool", toolName, "requestId", String.valueOf(request.getId())),
            true);
    }

    /**
     * A call made after the turn parked. It does not run (it may depend on the parked one, or be
     * gated itself), but it is recorded as its own pending approval so approving it later runs it.
     * An identical call already queued is not recorded again; past the limit - or when the caller
     * says the tool cannot be meaningfully replayed ({@code recordable}=false: dispatch plumbing
     * such as search_tools/call_tool, which approval replay cannot resolve) - the call is refused
     * without a record.
     */
    Outcome queueBehindPark(String toolName, String toolInput) {
        return queueBehindPark(toolName, toolInput, true);
    }

    Outcome queueBehindPark(String toolName, String toolInput, boolean recordable) {
        String parked = state.parkedTool();
        String key = TurnApprovalState.callKey(toolName, toolInput);
        String already = state.recordedRequestId(key);
        SandboxApprovalGrants.Grant isolation = state.sandboxGrant();
        if (isolation != null && isolation.isolated()) {
            return queueInSandbox(isolation, toolName, key, already, parked);
        }
        if (already != null) {
            return queued(toolName, already, parked);
        }
        if (!recordable) {
            return new Outcome(
                text(GuardrailPrompts.PARK_NOT_QUEUED, texts.getNotQueuedMessage(), "tool", toolName, "parkedTool", parked), false);
        }
        if (state.queuedCount() >= texts.getMaxQueuedCalls()) {
            return new Outcome(
                text(GuardrailPrompts.PARK_QUEUE_FULL, texts.getQueueFullMessage(), "tool", toolName, "parkedTool", parked), false);
        }
        ApprovalRequest request = record(
            GuardedToolCallback.ACTION_TYPE_PREFIX + toolName, toolName, toolInput,
            approvalSummaryFor(toolName, toolInput) + " [queued behind: " + parked + "]");
        if (request == null) {
            return failed(toolName);
        }
        return queued(toolName, state.rememberQueued(key, String.valueOf(request.getId())), parked);
    }

    /**
     * The held call of an isolated eval turn: the turn parks exactly as in production (so the model stops and the
     * case reports a call held for approval), but no request is recorded and nobody is notified.
     */
    private Outcome holdInSandbox(SandboxApprovalGrants.Grant isolation, String toolName, String toolInput) {
        String id = SANDBOX_REQUEST_PREFIX + UUID.randomUUID();
        isolation.recordHeld(toolName);
        state.markParked(toolName, TurnApprovalState.callKey(toolName, toolInput), id);
        return new Outcome(
            text(GuardrailPrompts.PARK_AWAITING, texts.getAwaitingMessage(), "tool", toolName, "requestId", id), true);
    }

    private Outcome queueInSandbox(
        SandboxApprovalGrants.Grant isolation, String toolName, String key, String already, String parked
    ) {
        if (already != null) {
            return queued(toolName, already, parked);
        }
        if (state.queuedCount() >= texts.getMaxQueuedCalls()) {
            return new Outcome(
                text(GuardrailPrompts.PARK_QUEUE_FULL, texts.getQueueFullMessage(), "tool", toolName, "parkedTool", parked), false);
        }
        isolation.recordHeld(toolName);
        return queued(toolName, state.rememberQueued(key, SANDBOX_REQUEST_PREFIX + UUID.randomUUID()), parked);
    }

    private Outcome queued(String toolName, String requestId, String parkedTool) {
        return new Outcome(
            text(GuardrailPrompts.PARK_QUEUED, texts.getQueuedMessage(), "tool", toolName, "requestId", requestId, "parkedTool", parkedTool), true);
    }

    private Outcome failed(String toolName) {
        return new Outcome(text(GuardrailPrompts.PARK_RECORD_FAILED, texts.getRecordFailedMessage(), "tool", toolName), false);
    }

    /** The recorded request, or {@code null} when recording failed (logged; never thrown). */
    private ApprovalRequest record(String actionType, String toolName, String toolInput, String summary) {
        try {
            // Records a replay envelope (tool name, exact args, agent/session, the scoped memory id,
            // and the current thread's tenant/subject - this runs inside
            // TenantContextPropagatingToolCallback's restored context, so "current" is genuinely the
            // originating turn's) so an approval later can actually re-run this call.
            String requestedBy = "agent:" + agentId + ":session:" + sessionId;
            String digest = ToolDefinitionDigest.of(delegate.getToolDefinition());
            // A turn that knows its goal records it (and who it ran as) so the task can be continued
            // once a human decides; a turn that does not (edge execution, a delegated call) records
            // the scoped history id alone, as before.
            ToolCallOrigin origin = state.origin(texts.getMaxGoalChars(), texts.getGoalCutMarker());
            ApprovalRequest request = origin == null
                ? gate.recordToolCallForApproval(
                    actionType, toolName, summary, toolInput, requestedBy, agentId, sessionId, digest,
                    state.memorySessionId())
                : gate.recordToolCallWithOrigin(
                    actionType, toolName, summary, toolInput, requestedBy, agentId, sessionId, digest, origin);
            if (request == null) {
                log.warn("Approval gate returned no request for gated tool '{}' (agent {}, session {})",
                    toolName, agentId, sessionId);
            }
            return request;
        } catch (RuntimeException e) {
            log.warn("Could not record the approval request for gated tool '{}' (agent {}, session {}); the "
                + "model is told it was not executed and nothing is waiting", toolName, agentId, sessionId, e);
            return null;
        }
    }

    /**
     * The confirmation card's summary line: the tool's own plain-language description of THIS call when it
     * implements {@link ToolConfirmationDescriber}, otherwise the historic bare {@code "Tool call: <name>"}. Any
     * failure in the describer falls back to the default - describing a call must never stop it being recorded.
     */
    private String approvalSummaryFor(String toolName, String toolInput) {
        String summary = "Tool call: " + toolName;
        if (delegate instanceof ToolConfirmationDescriber describer) {
            try {
                String described = describer.describeForConfirmation(toolInput);
                if (described != null && !described.isBlank()) {
                    summary = described;
                }
            } catch (RuntimeException e) {
                log.debug("Tool {} could not describe its call for confirmation; using the default summary", toolName, e);
            }
        }
        return summary;
    }
}
