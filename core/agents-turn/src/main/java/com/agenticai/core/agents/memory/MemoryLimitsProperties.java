package com.agenticai.core.agents.memory;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code app.memory.limits.*}: how much the long-term and session memories hold and how many of them a
 * turn recalls. These were constants in the services (50 per agent, 300 per tenant, recall top 5, 400
 * stored session entries, summary fold batch 40); the values here are those numbers, so nothing changes
 * until an operator sets one.
 *
 * <p>The defaults are written here once; {@code application.yml} repeats them so an operator can see and
 * change them, and {@code MemoryLimitsDefaultsTest} pins the two together. Registered by {@link
 * MemoryLimitsConfiguration}.
 */
@ConfigurationProperties(prefix = "app.memory.limits")
public class MemoryLimitsProperties {

    /** The shipped storage bound, for readers that have no bean (see {@code SessionMemoryService#MAX_STORED_ENTRIES}). */
    public static final int DEFAULT_SESSION_MAX_STORED_ENTRIES = 400;

    /** The shipped fold batch. */
    public static final int DEFAULT_SESSION_FOLD_BATCH = 40;

    /** Stored long-term memories one agent keeps across every subject; the oldest are pruned past it. */
    private int agentMaxMemories = 50;

    /** Stored shared memories one tenant keeps (memory blocks excluded); the oldest are pruned past it. */
    private int tenantMaxMemories = 300;

    /** Memories an agent's automatic recall injects into a turn. */
    private int agentRecallTopK = 5;

    /** Shared (tenant) memories a turn's automatic recall injects. */
    private int tenantRecallTopK = 5;

    /**
     * Turns kept in a session's Redis list; the hard storage bound, and the ceiling of the replay window
     * {@code app.chat.history-max-turns}.
     */
    private int sessionMaxStoredEntries = DEFAULT_SESSION_MAX_STORED_ENTRIES;

    /**
     * How many out-of-window turns are folded into the rolling summary at a time (one summarisation call per
     * this many turns). Kept below {@link #getSessionMaxStoredEntries()} so a batch is always claimed before
     * the storage trim can reach an unfolded turn.
     */
    private int sessionFoldBatch = DEFAULT_SESSION_FOLD_BATCH;

    /**
     * Facts the edge (on-device) memory endpoint returns. Small on purpose: they go into the system prompt of a
     * 4k to 8k context window, once per session, ranked by nothing the turn asked.
     */
    private int edgeMemoryMaxFacts = 24;

    public int getEdgeMemoryMaxFacts() {
        return edgeMemoryMaxFacts;
    }

    public void setEdgeMemoryMaxFacts(int edgeMemoryMaxFacts) {
        this.edgeMemoryMaxFacts = Math.max(1, edgeMemoryMaxFacts);
    }

    public int getAgentMaxMemories() {
        return agentMaxMemories;
    }

    public void setAgentMaxMemories(int agentMaxMemories) {
        this.agentMaxMemories = Math.max(1, agentMaxMemories);
    }

    public int getTenantMaxMemories() {
        return tenantMaxMemories;
    }

    public void setTenantMaxMemories(int tenantMaxMemories) {
        this.tenantMaxMemories = Math.max(1, tenantMaxMemories);
    }

    public int getAgentRecallTopK() {
        return agentRecallTopK;
    }

    public void setAgentRecallTopK(int agentRecallTopK) {
        this.agentRecallTopK = Math.max(1, agentRecallTopK);
    }

    public int getTenantRecallTopK() {
        return tenantRecallTopK;
    }

    public void setTenantRecallTopK(int tenantRecallTopK) {
        this.tenantRecallTopK = Math.max(1, tenantRecallTopK);
    }

    public int getSessionMaxStoredEntries() {
        return sessionMaxStoredEntries;
    }

    public void setSessionMaxStoredEntries(int sessionMaxStoredEntries) {
        this.sessionMaxStoredEntries = Math.max(2, sessionMaxStoredEntries);
    }

    public int getSessionFoldBatch() {
        return sessionFoldBatch;
    }

    public void setSessionFoldBatch(int sessionFoldBatch) {
        this.sessionFoldBatch = Math.max(1, sessionFoldBatch);
    }

    /** The fold batch actually used: never as large as the storage bound, so the trim cannot outrun the fold. */
    public int effectiveFoldBatch() {
        return Math.min(sessionFoldBatch, sessionMaxStoredEntries - 1);
    }
}
