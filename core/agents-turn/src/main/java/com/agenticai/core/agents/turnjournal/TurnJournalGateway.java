package com.agenticai.core.agents.turnjournal;

import java.util.UUID;

/**
 * Process-wide switch for the turn journal. ChatTurnExecutor is constructed by hand (not a bean),
 * so it reaches the journal through this holder, installed once at startup by {@code
 * TurnJournalConfig}. Disabled (the default, {@code app.turn-journal.enabled=false}) every {@link
 * #begin} returns {@link TurnRecorder#NOOP} and behaviour is unchanged.
 */
public final class TurnJournalGateway {

    /**
     * The response header that carries the id of the journaled turn (only while the journal is enabled), so a
     * client can open the turn it just ran: {@code GET /api/agents/{agentId}/turns/{id}/journal}.
     */
    public static final String TURN_ID_HEADER = "X-Journal-Turn-Id";

    private static volatile TurnJournal journal;
    private static volatile boolean enabled;

    private TurnJournalGateway() {}

    public static void install(TurnJournal journalImpl, boolean enabledFlag) {
        journal = journalImpl;
        enabled = enabledFlag;
    }

    /** True once {@link #install} ran (with any flag): the startup check uses it to tell "never installed". */
    public static boolean installed() {
        return journal != null;
    }

    public static boolean enabled() {
        return enabled && journal != null;
    }

    public static TurnRecorder begin(UUID agentId) {
        return begin(agentId, null, null);
    }

    /** The recorder of the turn {@code turnId} of session {@code sessionId}; {@link TurnRecorder#NOOP} when disabled. */
    public static TurnRecorder begin(UUID agentId, UUID turnId, String sessionId) {
        return enabled() ? TurnRecorder.start(journal, agentId, turnId, sessionId) : TurnRecorder.NOOP;
    }
}
