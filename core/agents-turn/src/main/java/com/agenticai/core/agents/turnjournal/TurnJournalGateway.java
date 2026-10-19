package com.agenticai.core.agents.turnjournal;

import java.util.UUID;

/**
 * Process-wide switch for the turn journal. ChatTurnExecutor is constructed by hand (not a bean),
 * so it reaches the journal through this holder, installed once at startup by {@code
 * TurnJournalConfig}. Disabled (the default, {@code app.turn-journal.enabled=false}) every {@link
 * #begin} returns {@link TurnRecorder#NOOP} and behaviour is unchanged.
 */
public final class TurnJournalGateway {

    private static volatile TurnJournal journal;
    private static volatile boolean enabled;

    private TurnJournalGateway() {}

    public static void install(TurnJournal journalImpl, boolean enabledFlag) {
        journal = journalImpl;
        enabled = enabledFlag;
    }

    public static boolean enabled() {
        return enabled && journal != null;
    }

    public static TurnRecorder begin(UUID agentId) {
        return enabled() ? TurnRecorder.start(journal, agentId) : TurnRecorder.NOOP;
    }
}
