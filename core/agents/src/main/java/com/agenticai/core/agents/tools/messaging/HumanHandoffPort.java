package com.agenticai.core.agents.tools.messaging;

/**
 * "Let an agent ask for a human" - same cross-module inversion as {@link
 * ChannelMediaSendPort}/{@link ChannelMessageQueryPort} for the same reason (see those interfaces'
 * javadoc): the real escalation logic - flipping a {@code channel_session} to {@code
 * AWAITING_HUMAN} and notifying the tenant's operators - lives in {@code core/omnichannel}'s {@code
 * HandoffEscalationService}, which already depends on {@code core/agents}, so {@code core/agents}
 * cannot depend back on it without a circular Maven module dependency. This port is defined here,
 * in {@code core/agents} (where {@link RequestHumanToolCallback} needs it), and implemented over in
 * {@code core/omnichannel} (where the escalation state actually lives) - Spring wires the two
 * together at runtime with no compile-time dependency in either direction beyond the one that
 * already exists.
 *
 * <p>This is the exact seam {@code HandoffEscalationService. requestHumanForAgentSession}'s own
 * javadoc names and describes as designed-but-not-yet-written: "the shape here (and the {@code
 * HandoffResult} record) already matches the designed port contract exactly" - {@link
 * HandoffOutcome} below mirrors that record field-for- field so the real implementation can be a
 * one-line delegate.
 *
 * <p><b>Never throws.</b> Same never-throws contract {@link ChannelMediaSendPort#sendMedia} and
 * {@code HandoffEscalationService.requestHumanForAgentSession} both already carry - a tool-call
 * result that surfaces an exception kills the whole model turn (see {@code
 * TenantContextPropagatingToolCallback}'s 2026-08-13 note). An implementation must catch its own
 * failures and return {@code HandoffOutcome(false, "<safe-to-read-aloud detail>")} instead.
 */
public interface HumanHandoffPort {

    /**
     * @param agentSessionId the agent-facing conversation-memory session id for THIS conversation -
     *     the same {@code sessionId} {@link RequestHumanToolCallback} (and every other builtin
     *     tool) is constructed with, not an internal {@code channel_session} row id.
     * @param reason why a human is needed - shown verbatim in the operator inbox (subject to
     *     whatever length limit the implementation's underlying entity enforces).
     * @return the outcome. {@link HandoffOutcome#detail} is written to be safe for a model to
     *     paraphrase to the end customer - never an internal id or operator name.
     */
    HandoffOutcome requestHuman(String agentSessionId, String reason);

    record HandoffOutcome(boolean escalated, String detail) {}
}
