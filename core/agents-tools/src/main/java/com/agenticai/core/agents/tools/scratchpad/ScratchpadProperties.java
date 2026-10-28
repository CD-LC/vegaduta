package com.agenticai.core.agents.tools.scratchpad;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code app.tools.scratchpad.*} - the operator-facing bounds of the agent scratchpad.
 *
 * <p>{@link #enabled} is the master switch; on by default - unlike {@code tool-sandbox.enabled}
 * there is no external service to point at, the storage is just a tenant-schema table, so "on"
 * costs nothing until an agent actually writes.
 *
 * <p>{@link #maxNotes} and {@link #maxNoteBytes} are the platform's ceilings, not the agent's
 * preference: {@code AgentScratchpadService} rejects what exceeds them at write time, so tightening
 * or loosening either needs no migration (the columns are TEXT/INTEGER either way).
 */
@ConfigurationProperties(prefix = "app.tools.scratchpad")
public class ScratchpadProperties {

    /** Master switch. Default true - the feature is pure storage inside the tenant schema. */
    private boolean enabled = true;

    /**
     * Standing cap on notes per {@code (agent, session)} scope; the agent-long-term (null session)
     * scope is its own budget.
     */
    private int maxNotes = 200;

    /** Largest single note body core will accept, in UTF-8 bytes. */
    private int maxNoteBytes = 65536;

    /**
     * How a write to a full scope is answered: {@code true} evicts the least-recently-updated note
     * so the new one lands (the agent is told which key it lost via {@code evicted_key}); {@code
     * false} keeps the old behaviour - the write is refused with an error the model can fix by
     * deleting or rewriting a note.
     */
    private boolean evictOldestOnCap = true;

    /**
     * Age after which a session-scoped note is dropped, applied lazily on writes to that scope
     * (ISO-8601 duration syntax, e.g. {@code 30d}). Session ids are per-conversation, so a dead
     * session's notes would otherwise accumulate forever. {@code 0} disables.
     */
    private java.time.Duration sessionNoteTtl = java.time.Duration.ofDays(30);

    /**
     * Age after which an agent-long-term note is dropped, lazily on writes to that scope. Default
     * {@code 0} = never: the long-term pad is meant to outlive conversations.
     */
    private java.time.Duration agentNoteTtl = java.time.Duration.ZERO;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getMaxNotes() {
        return maxNotes;
    }

    public void setMaxNotes(int maxNotes) {
        this.maxNotes = maxNotes;
    }

    public int getMaxNoteBytes() {
        return maxNoteBytes;
    }

    public void setMaxNoteBytes(int maxNoteBytes) {
        this.maxNoteBytes = maxNoteBytes;
    }

    public boolean isEvictOldestOnCap() {
        return evictOldestOnCap;
    }

    public void setEvictOldestOnCap(boolean evictOldestOnCap) {
        this.evictOldestOnCap = evictOldestOnCap;
    }

    public java.time.Duration getSessionNoteTtl() {
        return sessionNoteTtl;
    }

    public void setSessionNoteTtl(java.time.Duration sessionNoteTtl) {
        this.sessionNoteTtl = sessionNoteTtl == null ? java.time.Duration.ZERO : sessionNoteTtl;
    }

    public java.time.Duration getAgentNoteTtl() {
        return agentNoteTtl;
    }

    public void setAgentNoteTtl(java.time.Duration agentNoteTtl) {
        this.agentNoteTtl = agentNoteTtl == null ? java.time.Duration.ZERO : agentNoteTtl;
    }
}
