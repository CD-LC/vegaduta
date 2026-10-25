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

    /** Standing cap on notes per {@code (agent, session)} scope; the agent-long-term (null session) scope is its own budget. */
    private int maxNotes = 200;

    /** Largest single note body core will accept, in UTF-8 bytes. */
    private int maxNoteBytes = 65536;

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
}
