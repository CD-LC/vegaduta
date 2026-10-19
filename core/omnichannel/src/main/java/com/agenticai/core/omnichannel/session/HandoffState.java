package com.agenticai.core.omnichannel.session;

/**
 * Who is handling a {@link ChannelSession}'s conversation right now - the
 * live-human-handoff primitive (migration V214). Persisted with
 * {@code @Enumerated(STRING)} against the {@code
 * channel_session_handoff_state_chk} CHECK constraint, so renaming a value
 * here without a data migration breaks reads for the whole table - same
 * contract as {@code AgentJobStatus} (V207).
 *
 * <p>The three states, and the one deliberate asymmetry between them:
 *
 * <ul>
 *   <li>{@link #AGENT} - normal operation. The default for every session
 *       ever created, including all rows that predate V214 (column
 *       {@code DEFAULT 'AGENT'}).</li>
 *   <li>{@link #AWAITING_HUMAN} - someone (an operator via {@code
 *       OperatorInboxController}, or eventually the designed {@code
 *       request_human} agent tool - see {@code HandoffEscalationService})
 *       has flagged this conversation as needing a person, and no person
 *       has claimed it yet. <b>The agent keeps replying in this state.</b>
 *       That is a considered choice, not an oversight: an unclaimed
 *       escalation must not silence the tenant's only responder - the
 *       operator may be minutes or hours away, and dead air is a worse
 *       failure for the end customer than an imperfect agent answer.
 *       Suppression starts when a human actually holds the conversation,
 *       not when one is merely wished for.</li>
 *   <li>{@link #HUMAN} - a named operator ({@link
 *       ChannelSession#getTakenOverBy()}) holds the conversation. The agent
 *       must NOT reply and the channel workflow trigger must NOT fire -
 *       enforced fail-closed in {@code ChannelSessionService#resolveOrCreate}
 *       via {@link ConversationHeldByHumanException} (see that class for the
 *       mechanism and its transaction subtlety).</li>
 * </ul>
 *
 * <p>{@link ChannelType#VOICE} sessions never leave {@link #AGENT}:
 * {@code HandoffEscalationService} and {@code OperatorInboxService} both
 * refuse them, because a live phone call streamed over a WebSocket cannot be
 * handed off by a state flag - the caller would just hear silence. Voice
 * handoff is a warm-transfer problem, out of scope here and said so honestly
 * rather than implying 10/10 channel coverage.
 */
public enum HandoffState {
    AGENT,
    AWAITING_HUMAN,
    HUMAN
}
