package com.agenticai.core.agents.turnjournal;

import com.agenticai.core.guardrails.ReadOnlyAutoAllow;
import com.agenticai.core.guardrails.TurnApprovalState;
import com.agenticai.core.security.TenantScope;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;

/**
 * Writes the events of ONE turn attempt. Created on the request thread (it captures the tenant scope and
 * re-applies it on whichever thread an event is written from), never throws into the turn, and
 * never receives raw prompts: callers pass the text only to be hashed. {@link #NOOP} is the
 * disabled recorder, so call sites need no flag checks.
 *
 * <p>The turn id is the turn's own ({@code TurnContext#turnId}), not made up here, so it can be handed to the client
 * before the first event is written and every attempt of one turn (a hosted-chain failover runs the turn again on the
 * next provider) appends to the same chain. The session id is recorded as an index and, as a digest, in the INTAKE
 * summary.
 */
public final class TurnRecorder {

    private static final Logger log = LoggerFactory.getLogger(TurnRecorder.class);

    public static final TurnRecorder NOOP = new TurnRecorder(null, null, null, null, null);

    /** How a journaled tool call ended, as far as the journal can tell from the outside. */
    public enum CallStatus {
        /** The callback returned a result the platform did not write. */
        OK("ok"),
        /** The callback threw. */
        ERROR("error"),
        /** A guard, policy, hook, argument check, circuit breaker or budget answered instead of the tool. */
        BLOCKED("blocked"),
        /** The call was held for a human decision; the tool has not run. */
        PARKED("parked"),
        /** An offline eval turn's sandbox answered with a simulated result; the tool has not run. */
        SIMULATED("simulated");

        private final String label;

        CallStatus(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** The journal turn whose tool call is executing on this thread, so a sub-agent turn can name its parent. */
    private static final ThreadLocal<UUID> CURRENT_TURN = new ThreadLocal<>();

    private final TurnJournal journal;
    private final UUID agentId;
    private final UUID turnId;
    private final String sessionId;
    private final TenantScope scope;
    private final AtomicBoolean committed = new AtomicBoolean();

    public TurnRecorder(TurnJournal journal, UUID agentId, UUID turnId, TenantScope scope) {
        this(journal, agentId, turnId, null, scope);
    }

    public TurnRecorder(
            TurnJournal journal, UUID agentId, UUID turnId, String sessionId, TenantScope scope) {
        this.journal = journal;
        this.agentId = agentId;
        this.turnId = turnId;
        this.sessionId = sessionId;
        this.scope = scope;
    }

    public static TurnRecorder start(TurnJournal journal, UUID agentId) {
        return start(journal, agentId, null, null);
    }

    /**
     * A recorder for the turn {@code turnId} of chat session {@code sessionId}; a fresh turn id when {@code turnId}
     * is null (a replay or a test fixture that has no turn context).
     */
    public static TurnRecorder start(
            TurnJournal journal, UUID agentId, UUID turnId, String sessionId) {
        if (journal == null || agentId == null) {
            return NOOP;
        }
        return new TurnRecorder(
                journal,
                agentId,
                turnId == null ? UUID.randomUUID() : turnId,
                sessionId,
                TenantScope.capture());
    }

    public boolean active() {
        return journal != null;
    }

    public UUID turnId() {
        return turnId;
    }

    /** The journal turn whose tool call is running on the calling thread, or null (see {@link #CURRENT_TURN}). */
    public static UUID currentTurnId() {
        return CURRENT_TURN.get();
    }

    /** Binds this turn as the calling thread's current one until {@link #leave} is called with the result. */
    UUID enter() {
        UUID previous = CURRENT_TURN.get();
        if (active()) {
            CURRENT_TURN.set(turnId);
        }
        return previous;
    }

    /** Restores what {@link #enter} returned. */
    static void leave(UUID previous) {
        if (previous == null) {
            CURRENT_TURN.remove();
        } else {
            CURRENT_TURN.set(previous);
        }
    }

    public void intake(String userMessage, int attachments, int tools) {
        intake(userMessage, attachments, tools, null);
    }

    /**
     * As {@link #intake(String, int, int)}; {@code parentTurnId} is the journal turn that started this one (a
     * delegated or specialist turn started by a tool call of its parent), or null.
     */
    public void intake(String userMessage, int attachments, int tools, UUID parentTurnId) {
        intake(userMessage, attachments, tools, parentTurnId, null);
    }

    /**
     * As above, for a turn narrowed to a run-scoped tool selection: {@code runTools} (null = not narrowed, an empty
     * list = narrowed to no tools) is written as {@code runTools=a,b}, so the turn timeline shows which tools this
     * run was limited to. {@code tools} is then the count after narrowing.
     */
    public void intake(
            String userMessage, int attachments, int tools, UUID parentTurnId, List<String> runTools) {
        String session =
                sessionId == null ? "" : " session=" + TurnHashChain.sha256(sessionId).substring(0, 12);
        record(
                TurnEventType.INTAKE,
                TurnHashChain.sha256(userMessage),
                "messageChars=" + (userMessage == null ? 0 : userMessage.length())
                        + " attachments=" + attachments + " tools=" + tools + session + runToolsField(runTools),
                parentTurnId == null ? "" : " parent=" + parentTurnId);
    }

    /** How many characters of the tool list the INTAKE summary keeps; the summary as a whole is capped. */
    private static final int MAX_RUN_TOOLS_CHARS = 120;

    private static String runToolsField(List<String> runTools) {
        if (runTools == null) {
            return "";
        }
        if (runTools.isEmpty()) {
            return " runTools=none";
        }
        String joined = String.join(",", runTools);
        if (joined.length() > MAX_RUN_TOOLS_CHARS) {
            int keep = joined.lastIndexOf(',', MAX_RUN_TOOLS_CHARS);
            int shown = keep < 0 ? 0 : joined.substring(0, keep).split(",").length;
            joined = (keep < 0 ? "" : joined.substring(0, keep) + ",") + "+" + (runTools.size() - shown) + "more";
        }
        return " runTools=" + joined;
    }

    public void guard(String name, String outcome) {
        record(TurnEventType.GUARD, TurnHashChain.sha256(name + ":" + outcome), "guard=" + name + " outcome=" + outcome);
    }

    public void modelCall(String provider, String model, String route) {
        record(
                TurnEventType.MODEL_CALL,
                TurnHashChain.sha256(provider + "/" + model + "/" + route),
                "provider=" + provider + " model=" + model + " route=" + route);
    }

    public void approval(String toolName, String decision) {
        approval(toolName, decision, null);
    }

    /**
     * An approval event: {@code decision} is {@code pending} when a tool call was held for a human, {@code
     * decided:completed}, {@code decided:failed} or {@code decided:denied} on the turn that continues the task after
     * the human decided. {@code requestId} links the two (the approval request both refer to), or is null.
     */
    public void approval(String toolName, String decision, UUID requestId) {
        record(
                TurnEventType.APPROVAL,
                TurnHashChain.sha256(toolName + ":" + decision + ":" + requestId),
                "tool=" + toolName + " decision=" + decision,
                requestId == null ? "" : " request=" + requestId);
    }

    /**
     * An approval event for a call that needed NO human: a SUPERVISED agent's explicitly classified read-only tool
     * ({@code app.guardrails.supervised.gate-read-only=false}). Written as {@code decision=auto-allowed
     * reason=read-only-under-SUPERVISED} (one token each: the inspector splits a summary on whitespace), so the turn
     * shows that the platform, not a person, let the call through, and why.
     */
    public void approvalAutoAllowed(String toolName) {
        record(
                TurnEventType.APPROVAL,
                TurnHashChain.sha256(toolName + ":" + ReadOnlyAutoAllow.JOURNAL_DECISION),
                "tool=" + toolName + " decision=" + ReadOnlyAutoAllow.JOURNAL_DECISION
                        + " reason=" + ReadOnlyAutoAllow.JOURNAL_REASON);
    }

    public void verify(String check, boolean passed) {
        record(
                TurnEventType.VERIFY,
                TurnHashChain.sha256(check + ":" + passed),
                "check=" + check + " passed=" + passed);
    }

    /** payloadHash is the hash of the tool ARGUMENTS; the result is summarised by status and a short digest. */
    public void toolCall(String tool, String arguments, String result, boolean ok, long millis) {
        toolCall(tool, arguments, result, ok ? CallStatus.OK : CallStatus.ERROR, millis);
    }

    /** As above with the call's {@link CallStatus}: a refused or parked call is not an {@code ok} call. */
    public void toolCall(String tool, String arguments, String result, CallStatus status, long millis) {
        String resultDigest = TurnHashChain.sha256(result).substring(0, 12);
        record(
                TurnEventType.TOOL_CALL,
                TurnHashChain.sha256(arguments),
                "tool=" + tool + " status=" + status.label() + " resultSha=" + resultDigest
                        + " ms=" + millis);
    }

    /** At most one COMMIT per attempt; {@code status} is e.g. completed, error, cancelled. */
    public void commit(String answer, String status) {
        if (!active() || !committed.compareAndSet(false, true)) {
            return;
        }
        record(
                TurnEventType.COMMIT,
                TurnHashChain.sha256(answer),
                "status=" + status + " answerChars=" + (answer == null ? 0 : answer.length()));
    }

    /** Wraps each tool so every invocation is journaled; the same list when this recorder is off. */
    public List<ToolCallback> wrapTools(List<ToolCallback> tools) {
        return wrapTools(tools, null);
    }

    /**
     * As above; with the turn's {@code approval} state a call held for a human is recognised and journaled as an
     * APPROVAL event and a {@code parked} call (see {@link JournalingToolCallback}).
     */
    public List<ToolCallback> wrapTools(List<ToolCallback> tools, TurnApprovalState approval) {
        if (!active() || tools == null || tools.isEmpty()) {
            return tools;
        }
        return tools.stream()
                .<ToolCallback>map(t -> new JournalingToolCallback(t, this, approval))
                .toList();
    }

    private void record(TurnEventType type, String payloadHash, String summary) {
        record(type, payloadHash, summary, "");
    }

    /**
     * {@code summary} is redacted; {@code ids} is appended after the redaction and is built from {@link UUID}s only,
     * because the redaction would otherwise take a UUID for a token and mask it.
     */
    private void record(TurnEventType type, String payloadHash, String summary, String ids) {
        if (!active()) {
            return;
        }
        try {
            String safe = TurnRedaction.summary(summary) + ids;
            scope.run(() -> journal.append(agentId, turnId, sessionId, type, payloadHash, safe));
        } catch (RuntimeException e) {
            log.warn("turn journal append failed for turn {} ({}): {}", turnId, type, e.toString());
        }
    }
}
