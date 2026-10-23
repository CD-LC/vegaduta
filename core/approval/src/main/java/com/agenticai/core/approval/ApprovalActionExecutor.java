package com.agenticai.core.approval;

/**
 * Implemented by anything that performs a real, gated action once an
 * {@link ApprovalRequest} for it is approved - e.g. the Omnichannel Build
 * Plan's Stage 2 Twilio adapter implementing this for action type
 * {@code "channel.whatsapp.send"}. Every Spring-managed bean implementing
 * this interface is picked up automatically by
 * {@link ApprovalActionExecutorRegistry} - registering a new gated action
 * type is "add a bean implementing this interface," not a change to
 * {@link HumanApprovalGateService} itself.
 */
public interface ApprovalActionExecutor {

    /**
     * The action type this executor handles, matching what callers pass to
     * {@link HumanApprovalGateService#evaluate}. Must be unique across all
     * registered executors - {@link ApprovalActionExecutorRegistry} treats a
     * collision as a startup configuration error, not a silent overwrite.
     */
    String actionType();

    /**
     * Whether this executor can handle {@code actionType} - the seam that
     * lets one executor own a FAMILY of dynamic action types (the gated
     * agent-tool replay executor matches every {@code "mcp_tool.<name>"},
     * where {@code <name>} is only known at runtime) without registering a
     * bean per tool. The default is exact equality with {@link #actionType},
     * so every pre-existing fixed-action-type executor behaves identically
     * with no changes. {@link ApprovalActionExecutorRegistry#find} consults
     * this ONLY when no exact {@link #actionType} match exists, so a
     * specific executor always beats a family match.
     */
    default boolean supports(String actionType) {
        return actionType().equals(actionType);
    }

    /**
     * Actually perform the action. Implementations should throw an unchecked
     * exception (a {@link com.agenticai.core.omnichannel.ChannelException}
     * subtype for channel adapters) on failure - the gate service records
     * the failure against the request and re-throws rather than swallowing
     * it.
     */
    void execute(ApprovalRequest request);

    /**
     * Notification that a human REJECTED this request - the seam that lets
     * an executor whose pending request left external state suspended (a
     * workflow run parked AWAITING_APPROVAL is the motivating case, see
     * {@code WorkflowApprovalActionExecutor}) settle that state into an
     * honest terminal form instead of leaving it waiting forever for an
     * execution that will never come. Default no-op: for most action types
     * (channel sends, gated tool calls) a rejection means "never do it" and
     * there is genuinely nothing to clean up, so every pre-existing executor
     * keeps its exact behavior with no changes.
     *
     * <p>Called by {@link HumanApprovalGateService#reject} AFTER the
     * rejection is recorded, and strictly best-effort from the caller's side
     * (an exception here is logged, never propagated) - the human's decision
     * must stand regardless of what cleanup does. Implementations must never
     * perform the gated action itself from this hook.
     */
    default void onRejected(ApprovalRequest request, String decidedBy, String reason) {
        // no cleanup needed for most action types
    }

    /**
     * True when this executor itself puts the outcome of the action back into the conversation that
     * asked for it (and continues the task) - the gated agent-tool replay executor does. For every other
     * executor {@link HumanApprovalGateService} reports the outcome through {@link ApprovalOutcomePort}
     * after {@link #execute} returned, failed for good, or the request was rejected or expired, so a
     * request raised from an agent turn resumes the agent whichever executor ran it. Reporting twice is
     * harmless to the conversation (the continuation is claimed once) but would write the history note
     * twice, hence this flag.
     */
    default boolean reportsOutcomeItself() {
        return false;
    }
}
