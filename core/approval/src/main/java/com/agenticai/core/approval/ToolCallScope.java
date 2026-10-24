package com.agenticai.core.approval;

import java.util.Optional;
import java.util.UUID;

/**
 * The agent tool call that is running on the calling thread: which tool, for which agent and
 * conversation, and where the call came from ({@link ToolCallOrigin}: the user's goal, the scoped
 * history id, the memory subject).
 *
 * <p>Why this exists. Most gated tool calls are held by the tool guard, which records them with a
 * replay envelope. A few tools gate THEMSELVES inside {@code call()} (a code-workspace run or
 * apply, a channel send): they ask {@link HumanApprovalGateService#evaluateInTurn} for an approval
 * and return "pending" to the model. Those tools cannot see the turn, and this module cannot depend
 * on the turn path, so the guard opens a scope around the tool's {@code call()} and {@code
 * evaluateInTurn} reads it. The request is then recorded with the same envelope a guarded call has,
 * which is what lets the platform put the decision back into the conversation and carry the task on.
 *
 * <p>Thread-local on purpose, like {@link ApprovalReplayGrant}: the tool runs synchronously inside
 * the guard on the same thread, and a scope can therefore never reach a concurrent turn. Scopes nest
 * (a tool that runs another guarded tool): closing restores the outer one.
 */
public final class ToolCallScope {

    private static final ThreadLocal<Current> CURRENT = new ThreadLocal<>();

    private ToolCallScope() {
    }

    /**
     * @param toolName the tool the model called
     * @param agentId the agent whose tool chain runs the call (the routed agent)
     * @param sessionId the raw client session id
     * @param toolDigest fingerprint of the tool definition, or {@code null}
     * @param origin the goal and conversation identity, or an origin with only the scoped history id
     */
    public record Current(String toolName, UUID agentId, String sessionId, String toolDigest, ToolCallOrigin origin) {
    }

    /** Opens the scope for the duration of one tool call; close it in a {@code finally}. */
    public static Scope open(String toolName, UUID agentId, String sessionId, String toolDigest, ToolCallOrigin origin) {
        Current previous = CURRENT.get();
        CURRENT.set(new Current(toolName, agentId, sessionId, toolDigest, origin));
        return new Scope(previous);
    }

    /** The tool call running on this thread, or empty outside one. */
    public static Optional<Current> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    /** Restores the enclosing scope (or none) on close. */
    public static final class Scope implements AutoCloseable {
        private final Current previous;

        private Scope(Current previous) {
            this.previous = previous;
        }

        @Override
        public void close() {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }
}
