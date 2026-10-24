package com.agenticai.core.guardrails;

/**
 * Test access to {@link TurnApprovalState}'s package-private marking, which {@code ApprovalParking} does in
 * production once an approval request is durably recorded. Lives in the guardrails package only for that.
 */
public final class ApprovalStateFixture {

    private ApprovalStateFixture() {}

    /** A turn state in which the call (tool, arguments) is held as pending approval request {@code requestId}. */
    public static TurnApprovalState heldCall(String tool, String arguments, String requestId) {
        TurnApprovalState state = new TurnApprovalState("scoped-memory-id");
        state.markParked(tool, TurnApprovalState.callKey(tool, arguments), requestId);
        return state;
    }
}
