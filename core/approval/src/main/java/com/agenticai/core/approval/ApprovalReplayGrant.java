package com.agenticai.core.approval;

/**
 * Single-use, same-thread authorization token that lets an approved-and-
 * replayed tool call pass BACK through {@code GuardedToolCallback}'s
 * approval gate without being re-recorded as a second pending request.
 *
 * <p>Why this exists at all: the agent-tool replay executor (see {@code
 * AgentToolCallApprovalExecutor} in {@code core/agents}) deliberately
 * re-resolves the approved tool through {@code
 * AgentToolProvider.resolveToolCandidatesFor} - the ONE existing guarded
 * chokepoint every hosted turn and every edge execution already uses -
 * rather than growing a second, unguarded resolution path. That means the
 * replayed call re-enters {@code GuardedToolCallback.call}, whose policy
 * check would (correctly, absent this class) see "this action type requires
 * approval" and record a NEW pending request instead of executing - an
 * infinite approve-record-approve loop. The human approval that triggered
 * the replay IS the approval; this grant is how that fact crosses from the
 * executor into the guard.
 *
 * <p>Deliberately narrow semantics, each one load-bearing:
 * <ul>
 *   <li><b>Thread-local:</b> the executor invokes the resolved callback
 *       synchronously on its own thread (unlike a streaming chat turn, no
 *       reactive hop sits between grant and check), so a plain {@code
 *       ThreadLocal} is reliable here - and it can never leak authorization
 *       to a concurrent turn on another thread.</li>
 *   <li><b>Exact action type:</b> {@link #consume} only matches the one
 *       action type the human actually approved ({@code "mcp_tool.<name>"}
 *       for a replayed tool, {@code "workflow.node.<type>"} for a resumed
 *       workflow node - {@code WorkflowNodeExecutor}'s gate is the second
 *       consumer, added with F-05's approval-resume) - a replayed action
 *       that somehow triggered a different gated action on the same thread
 *       would still be gated normally.</li>
 *   <li><b>Single use:</b> {@link #consume} clears the grant on match, so
 *       one approval authorizes exactly one execution - a second call to
 *       the same tool on the same thread re-enters the normal gate.</li>
 * </ul>
 *
 * <p>Lives in {@code core/approval} (not {@code core/guardrails}) because
 * both sides of the handshake already depend on this module - {@code
 * GuardedToolCallback} (guardrails &rarr; approval) checks it, the replay
 * executor (agents &rarr; approval) sets it - and putting it anywhere else
 * would invert an existing dependency for one class.
 */
public final class ApprovalReplayGrant {

    private static final ThreadLocal<String> AUTHORIZED_ACTION_TYPE = new ThreadLocal<>();

    private ApprovalReplayGrant() {
    }

    /**
     * Authorizes exactly one execution of {@code actionType} on the calling
     * thread. Callers MUST pair this with {@link #clear} in a {@code
     * finally} block - {@link #consume} clears on a successful match, but a
     * callback that fails before its gate check runs would otherwise leave
     * the grant dangling on a pooled thread.
     */
    public static void grant(String actionType) {
        AUTHORIZED_ACTION_TYPE.set(actionType);
    }

    /**
     * True (and clears the grant - single use) if the calling thread holds
     * a grant for exactly this action type; false otherwise, leaving any
     * non-matching grant in place for the call it was actually issued for.
     */
    public static boolean consume(String actionType) {
        String granted = AUTHORIZED_ACTION_TYPE.get();
        if (granted != null && granted.equals(actionType)) {
            AUTHORIZED_ACTION_TYPE.remove();
            return true;
        }
        return false;
    }

    /** Unconditional cleanup - see {@link #grant}'s pairing requirement. */
    public static void clear() {
        AUTHORIZED_ACTION_TYPE.remove();
    }
}
