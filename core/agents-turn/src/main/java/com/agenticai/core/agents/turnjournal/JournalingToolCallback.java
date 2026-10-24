package com.agenticai.core.agents.turnjournal;

import com.agenticai.core.guardrails.SandboxApprovalGrants;
import com.agenticai.core.guardrails.ToolResultMarker;
import com.agenticai.core.guardrails.TurnApprovalState;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

/**
 * Decorator that journals each invocation of a tool (name, argument hash, outcome digest).
 *
 * <p>It wraps the tool from the outside, around the guard chain, so a normal return does not mean the tool ran: a
 * guard that refuses a call, parks it for a human, or tells the model the budget is gone also returns an ordinary
 * result string. The status is therefore worked out from what the platform says about the result, not from the fact
 * that it returned: {@link ToolResultMarker} for a refusal or a park (with the old wording as a fallback), and the
 * turn's {@link TurnApprovalState} for a call that was recorded as a pending approval, which also becomes an APPROVAL
 * event. Only an exception is {@code error}.
 */
final class JournalingToolCallback implements ToolCallback {

    /** The approval decision the journal records for a gated call the offline eval sandbox approved. */
    static final String EVAL_APPROVED = "decided:eval-approved";

    private final ToolCallback delegate;
    private final TurnRecorder recorder;
    private final TurnApprovalState approval;

    JournalingToolCallback(ToolCallback delegate, TurnRecorder recorder) {
        this(delegate, recorder, null);
    }

    JournalingToolCallback(ToolCallback delegate, TurnRecorder recorder, TurnApprovalState approval) {
        this.delegate = delegate;
        this.recorder = recorder;
        this.approval = approval;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public ToolMetadata getToolMetadata() {
        return delegate.getToolMetadata();
    }

    @Override
    public String call(String toolInput) {
        return journaled(toolInput, () -> delegate.call(toolInput));
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        return journaled(toolInput, () -> delegate.call(toolInput, toolContext));
    }

    private String journaled(String input, Supplier<String> call) {
        long started = System.currentTimeMillis();
        String name = delegate.getToolDefinition().name();
        UUID outer = recorder.enter();
        SandboxApprovalGrants.Grant sandbox = approval == null ? null : approval.sandboxGrant();
        int approvedBefore = sandbox == null ? 0 : java.util.Collections.frequency(sandbox.usedTools(), name);
        try {
            String result = call.get();
            // A gated call the eval sandbox let through ran without a person deciding it; the journal says so in
            // its own word, so a turn timeline never reads as if someone approved it.
            if (sandbox != null && java.util.Collections.frequency(sandbox.usedTools(), name) > approvedBefore) {
                recorder.approval(name, EVAL_APPROVED);
            }
            record(name, input, result, System.currentTimeMillis() - started);
            return result;
        } catch (RuntimeException e) {
            recorder.toolCall(
                    name,
                    input,
                    e.getClass().getSimpleName(),
                    TurnRecorder.CallStatus.ERROR,
                    System.currentTimeMillis() - started);
            throw e;
        } finally {
            TurnRecorder.leave(outer);
        }
    }

    private void record(String name, String input, String result, long millis) {
        // A SUPERVISED agent's classified read-only call that ran with no human: the guard says so on the turn's state,
        // and the journal shows it before the call, as it shows a pending approval.
        if (approval != null && approval.takeAutoAllowedReadOnly(name, input)) {
            recorder.approvalAutoAllowed(name);
        }
        String requestId = approval == null ? null : approval.requestIdFor(name, input);
        java.util.Optional<ToolResultMarker.Kind> kind = ToolResultMarker.classify(result);
        TurnRecorder.CallStatus status;
        if (requestId != null || kind.filter(k -> k == ToolResultMarker.Kind.PARKED).isPresent()) {
            recorder.approval(name, "pending", uuidOrNull(requestId));
            status = TurnRecorder.CallStatus.PARKED;
        } else if (kind.filter(k -> k == ToolResultMarker.Kind.SANDBOXED).isPresent()) {
            status = TurnRecorder.CallStatus.SIMULATED;
        } else if (kind.isPresent()) {
            status = TurnRecorder.CallStatus.BLOCKED;
        } else {
            status = TurnRecorder.CallStatus.OK;
        }
        recorder.toolCall(name, input, result, status, millis);
    }

    private static UUID uuidOrNull(String text) {
        if (text == null) {
            return null;
        }
        try {
            return UUID.fromString(text);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
