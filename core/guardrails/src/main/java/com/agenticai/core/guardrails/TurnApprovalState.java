package com.agenticai.core.guardrails;

import com.agenticai.core.approval.ToolCallOrigin;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * What the approval gate knows about ONE chat turn, shared between the tool guard (which writes it)
 * and the turn loop (which must not keep prompting a model whose task is waiting on a human).
 *
 * <ul>
 *   <li><b>Parked.</b> Set by {@link GuardedToolCallback} once a gated call has been recorded as a
 *       pending approval. From then on nothing else runs in the turn, and the turn loop reads
 *       {@link #isParked()} instead of guessing from the model's wording that a reply is an
 *       approval wait.
 *   <li><b>Scoped memory session id.</b> The SEC-004 id the turn reads its history under. It goes
 *       into the replay envelope so the "approved action completed" note written after the human
 *       decides lands in the history the conversation's NEXT turn reads, not under the raw client
 *       session id nothing reads. {@code null} for a turn resolved without one (edge execution,
 *       delegated calls); the executor then derives it.
 *   <li><b>Origin.</b> The user message that started the turn (the goal), the agent id the
 *       conversation was requested under and its memory subject. They go into the replay envelope
 *       with the scoped memory id, so the platform can continue the original task after the human
 *       decides. For a turn that is itself a continuation the goal is the ORIGINAL one, not the
 *       platform-written continuation message.
 *   <li><b>Sticky tools.</b> Tool names the conversation already used, which tool selection keeps
 *       on offer whatever the message says: for the continuation turn after an approval (whatever
 *       its goal says) and for a short follow-up such as "yes" or "continue" (see {@code
 *       FollowUpTools}); empty on any other turn.
 *   <li><b>Run tools.</b> The run-scoped tool selection the turn was narrowed to (the playground's "this run
 *       only" switches), or {@code null} for a turn that was not narrowed. It goes into the replay envelope
 *       with the goal, so the turn that continues the task after a human decides is narrowed the same way,
 *       and tool resolution keeps these tools out of relevance trimming (a tool the person picked is not
 *       dropped for not matching the message).
 *   <li><b>Queued calls.</b> Calls the model made after the turn parked; each was recorded as its
 *       own pending approval so it is not lost, keyed by tool and arguments so a repeat of a parked
 *       or queued call is not recorded twice.
 * </ul>
 *
 * One instance per turn, held by the turn's {@code TurnContext}. A turn that has none gets a
 * per-step-counter fallback inside {@link GuardedToolCallback}; either way the instance is
 * reachable only from the turn and is collected with it.
 */
public final class TurnApprovalState {

    private final String memorySessionId;
    private final String goal;
    private final UUID requestedAgentId;
    private final String subjectKey;
    private final Set<String> stickyTools;
    private final java.util.List<String> runToolNames;
    private final AtomicReference<String> parkedTool = new AtomicReference<>();
    /** Request id of every call recorded for approval this turn (the parked one and the queued ones). */
    private final ConcurrentMap<String, String> recordedRequestIds = new ConcurrentHashMap<>();
    private final AtomicInteger queued = new AtomicInteger();
    /** Calls this turn that ran without approval because they were classified read-only under SUPERVISED, by call key. */
    private final ConcurrentMap<String, Integer> autoAllowedReadOnly = new ConcurrentHashMap<>();
    /**
     * The eval sandbox's auto-approval for this turn, or {@code null} (every ordinary turn). Set once,
     * when the turn's state is built, from the grant registered under the turn's request id; see {@link
     * SandboxApprovalGrants}.
     */
    private volatile SandboxApprovalGrants.Grant sandboxGrant;

    public TurnApprovalState(String memorySessionId) {
        this(memorySessionId, null, null, null, Set.of());
    }

    public TurnApprovalState(
        String memorySessionId, String goal, UUID requestedAgentId, String subjectKey, Set<String> stickyTools
    ) {
        this(memorySessionId, goal, requestedAgentId, subjectKey, stickyTools, null);
    }

    /** As above, for a turn narrowed to {@code runToolNames} (see {@link #runToolNames()}); {@code null} = not narrowed. */
    public TurnApprovalState(
        String memorySessionId, String goal, UUID requestedAgentId, String subjectKey, Set<String> stickyTools,
        java.util.List<String> runToolNames
    ) {
        this.runToolNames = runToolNames == null ? null : java.util.List.copyOf(runToolNames);
        this.memorySessionId = memorySessionId == null || memorySessionId.isBlank() ? null : memorySessionId;
        this.goal = goal == null || goal.isBlank() ? null : goal;
        this.requestedAgentId = requestedAgentId;
        this.subjectKey = subjectKey == null || subjectKey.isBlank() ? null : subjectKey;
        this.stickyTools = stickyTools == null ? Set.of() : Set.copyOf(stickyTools);
    }

    /**
     * Lets {@link GuardedToolCallback} skip the "needs a human" park for the tools {@code grant} permits.
     * Only the code that starts an offline eval turn calls this, with a grant it registered itself.
     */
    public void enableSandboxGrant(SandboxApprovalGrants.Grant grant) {
        this.sandboxGrant = grant;
    }

    /** The turn's eval-sandbox auto-approval, or {@code null} when the turn has none. */
    public SandboxApprovalGrants.Grant sandboxGrant() {
        return sandboxGrant;
    }

    /** The user message that started this turn (the original goal for a continuation), or {@code null}. */
    public String goal() {
        return goal;
    }

    /** Tool names to keep on offer this turn regardless of the goal; empty on an ordinary turn. */
    public Set<String> stickyTools() {
        return stickyTools;
    }

    /**
     * The run-scoped tool selection this turn was narrowed to; {@code null} when it was not (an empty list is a
     * turn narrowed to no tools). Never widens anything: it is the request's own list, kept so the journal can
     * show it, tool resolution can keep it out of relevance trimming and the approval gate can pass it on.
     */
    public java.util.List<String> runToolNames() {
        return runToolNames;
    }

    /**
     * What the replay envelope records about where a held call came from; {@code null} when the turn
     * has no goal to restate (then only the scoped memory id, if any, is recorded).
     */
    ToolCallOrigin origin(int maxGoalChars, String cutMarker) {
        if (goal == null || maxGoalChars <= 0 || requestedAgentId == null || subjectKey == null) {
            return null;
        }
        String capped = goal.length() <= maxGoalChars ? goal : goal.substring(0, maxGoalChars) + (cutMarker == null ? "" : cutMarker);
        return new ToolCallOrigin(memorySessionId, capped, requestedAgentId, subjectKey, runToolNames);
    }

    /** The SEC-004 scoped memory id this turn reads history under, or {@code null} when unknown. */
    public String memorySessionId() {
        return memorySessionId;
    }

    /** True once a gated call of this turn has been recorded as a pending approval. */
    public boolean isParked() {
        return parkedTool.get() != null;
    }

    /** The tool whose pending approval parked the turn, or {@code null} when not parked. */
    public String parkedTool() {
        return parkedTool.get();
    }

    /**
     * Marks the turn parked on {@code toolName}'s pending approval {@code requestId}; the first
     * parked tool wins. Call only after the request is recorded.
     */
    void markParked(String toolName, String callKey, String requestId) {
        recordedRequestIds.putIfAbsent(callKey, requestId);
        parkedTool.compareAndSet(null, toolName);
    }

    /** Request id already recorded for an identical call (parked or queued) this turn, or {@code null}. */
    String recordedRequestId(String callKey) {
        return recordedRequestIds.get(callKey);
    }

    /**
     * The id of the pending approval recorded for this exact call (tool and arguments) this turn, whether it
     * is the call that parked the turn or one queued behind it; {@code null} when the call was not held. What
     * lets the tool wrapper say in the turn's tool ledger that a call is waiting on a human.
     */
    public String requestIdFor(String toolName, String toolInput) {
        return recordedRequestIds.get(callKey(toolName, toolInput));
    }

    /**
     * Notes that this exact call (tool and arguments) is about to run without approval because the agent is SUPERVISED
     * and the tool is classified read-only (see {@link ReadOnlyAutoAllow}). Written by the guard; read once by whatever
     * journals the turn's tool calls, which sits around the guard and cannot see the guard's decision otherwise.
     */
    void markAutoAllowedReadOnly(String toolName, String toolInput) {
        autoAllowedReadOnly.merge(callKey(toolName, toolInput), 1, Integer::sum);
    }

    /**
     * True once, per recorded call, when this exact call was auto-allowed as read-only under SUPERVISED; the note is
     * consumed, so a repeat of the same call is reported only as often as it was actually let through.
     */
    public boolean takeAutoAllowedReadOnly(String toolName, String toolInput) {
        String key = callKey(toolName, toolInput);
        boolean[] was = {false};
        autoAllowedReadOnly.computeIfPresent(key, (k, n) -> {
            was[0] = true;
            return n <= 1 ? null : n - 1;
        });
        return was[0];
    }

    /** How many calls made after the park have been queued as their own approvals. */
    int queuedCount() {
        return queued.get();
    }

    /** Remembers the request recorded for a queued call; returns the earlier id if one raced in. */
    String rememberQueued(String callKey, String requestId) {
        String earlier = recordedRequestIds.putIfAbsent(callKey, requestId);
        if (earlier != null) {
            return earlier;
        }
        queued.incrementAndGet();
        return requestId;
    }

    /** The key a call is deduplicated by: the tool and its exact arguments. */
    static String callKey(String toolName, String toolInput) {
        return toolName + '\u0000' + toolInput;
    }
}
