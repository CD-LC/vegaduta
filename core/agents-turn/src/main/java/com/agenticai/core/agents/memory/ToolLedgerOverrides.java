package com.agenticai.core.agents.memory;

import com.agenticai.core.agents.turn.ToolLedgerProperties;
import com.agenticai.core.settings.SettingContext;
import com.agenticai.core.settings.SettingsResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The tool ledger's limits for the calling thread's tenant: {@code app.chat.tool-ledger.*} with the tenant's {@code
 * chat.toolLedger.*} settings applied. Used where the ledger is recorded (the tool guard of a turn) and where it is
 * shown to the model again (the session history), so the two always agree on whether there is a record at all.
 *
 * <p>The keys have the tenant scope only: the record is stored with the conversation and replayed from the session
 * history, which belongs to the workspace, not to one agent. With no catalog, no tenant on the thread, or a failing
 * read, the platform object is returned itself.
 */
public final class ToolLedgerOverrides {

    private static final Logger log = LoggerFactory.getLogger(ToolLedgerOverrides.class);

    public static final String ENABLED = "chat.toolLedger.enabled";
    public static final String REPLAY_ENABLED = "chat.toolLedger.replayEnabled";
    public static final String MAX_ENTRIES_PER_TURN = "chat.toolLedger.maxEntriesPerTurn";
    public static final String REPLAY_TURNS = "chat.toolLedger.replayTurns";
    public static final String MAX_REPLAY_CHARS_PER_TURN = "chat.toolLedger.maxReplayCharsPerTurn";

    private ToolLedgerOverrides() {}

    /** The ledger limits of the calling thread's tenant; {@code platform} itself when nothing differs. */
    public static ToolLedgerProperties effective(ToolLedgerProperties platform, SettingsResolver catalog) {
        if (catalog == null || platform == null) {
            return platform;
        }
        try {
            SettingContext ctx = SettingContext.tenant();
            boolean enabled = catalog.getBool(ENABLED, ctx);
            boolean replayEnabled = catalog.getBool(REPLAY_ENABLED, ctx);
            int maxEntries = catalog.getInt(MAX_ENTRIES_PER_TURN, ctx);
            int replayTurns = catalog.getInt(REPLAY_TURNS, ctx);
            int maxReplayChars = catalog.getInt(MAX_REPLAY_CHARS_PER_TURN, ctx);
            if (enabled == platform.isEnabled()
                    && replayEnabled == platform.isReplayEnabled()
                    && maxEntries == platform.getMaxEntriesPerTurn()
                    && replayTurns == platform.getReplayTurns()
                    && maxReplayChars == platform.getMaxReplayCharsPerTurn()) {
                return platform;
            }
            return platform.with(enabled, replayEnabled, maxEntries, replayTurns, maxReplayChars);
        } catch (RuntimeException e) {
            log.warn("Could not read the chat.toolLedger.* settings; using app.chat.tool-ledger.*: {}", e.toString());
            return platform;
        }
    }
}
