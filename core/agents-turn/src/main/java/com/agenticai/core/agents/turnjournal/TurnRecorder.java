package com.agenticai.core.agents.turnjournal;

import com.agenticai.core.security.TenantScope;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;

/**
 * Writes the events of ONE turn. Created on the request thread (it captures the tenant scope and
 * re-applies it on whichever thread an event is written from), never throws into the turn, and
 * never receives raw prompts: callers pass the text only to be hashed. {@link #NOOP} is the
 * disabled recorder, so call sites need no flag checks.
 */
public final class TurnRecorder {

    private static final Logger log = LoggerFactory.getLogger(TurnRecorder.class);

    public static final TurnRecorder NOOP = new TurnRecorder(null, null, null, null);

    private final TurnJournal journal;
    private final UUID agentId;
    private final UUID turnId;
    private final TenantScope scope;
    private final AtomicBoolean committed = new AtomicBoolean();

    public TurnRecorder(TurnJournal journal, UUID agentId, UUID turnId, TenantScope scope) {
        this.journal = journal;
        this.agentId = agentId;
        this.turnId = turnId;
        this.scope = scope;
    }

    public static TurnRecorder start(TurnJournal journal, UUID agentId) {
        if (journal == null || agentId == null) {
            return NOOP;
        }
        return new TurnRecorder(journal, agentId, UUID.randomUUID(), TenantScope.capture());
    }

    public boolean active() {
        return journal != null;
    }

    public UUID turnId() {
        return turnId;
    }

    public void intake(String userMessage, int attachments, int tools) {
        record(
                TurnEventType.INTAKE,
                TurnHashChain.sha256(userMessage),
                "messageChars=" + (userMessage == null ? 0 : userMessage.length())
                        + " attachments=" + attachments + " tools=" + tools);
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
        record(
                TurnEventType.APPROVAL,
                TurnHashChain.sha256(toolName + ":" + decision),
                "tool=" + toolName + " decision=" + decision);
    }

    public void verify(String check, boolean passed) {
        record(
                TurnEventType.VERIFY,
                TurnHashChain.sha256(check + ":" + passed),
                "check=" + check + " passed=" + passed);
    }

    /** payloadHash is the hash of the tool ARGUMENTS; the result is summarised by status and a short digest. */
    public void toolCall(String tool, String arguments, String result, boolean ok, long millis) {
        String resultDigest = TurnHashChain.sha256(result).substring(0, 12);
        record(
                TurnEventType.TOOL_CALL,
                TurnHashChain.sha256(arguments),
                "tool=" + tool + " status=" + (ok ? "ok" : "error") + " resultSha=" + resultDigest
                        + " ms=" + millis);
    }

    /** At most one COMMIT per turn; {@code status} is e.g. completed, error, cancelled. */
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
        if (!active() || tools == null || tools.isEmpty()) {
            return tools;
        }
        return tools.stream().<ToolCallback>map(t -> new JournalingToolCallback(t, this)).toList();
    }

    private void record(TurnEventType type, String payloadHash, String summary) {
        if (!active()) {
            return;
        }
        try {
            String safe = TurnRedaction.summary(summary);
            scope.run(() -> journal.append(agentId, turnId, type, payloadHash, safe));
        } catch (RuntimeException e) {
            log.warn("turn journal append failed for turn {} ({}): {}", turnId, type, e.toString());
        }
    }
}
