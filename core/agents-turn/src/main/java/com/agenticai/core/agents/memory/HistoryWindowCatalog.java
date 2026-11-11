package com.agenticai.core.agents.memory;

import com.agenticai.core.settings.SettingDefinition;
import com.agenticai.core.settings.SettingRisk;
import com.agenticai.core.settings.SettingScope;
import com.agenticai.core.settings.SettingType;
import com.agenticai.core.settings.SettingUnit;
import com.agenticai.core.settings.SettingsContributor;
import java.util.List;
import java.util.function.IntSupplier;
import org.springframework.beans.factory.ObjectProvider;

/**
 * The replay window of a conversation as workspace settings: how many earlier turns, characters and estimated tokens
 * of the history are sent to the model again on every message ({@code chat.history.maxTurns}, {@code .maxChars},
 * {@code .maxTokens}). It is the largest share of the prompt of a long conversation, so it is the first control for
 * prompt size and first-token latency.
 *
 * <p>The platform value is the one {@link SessionMemoryService} already reads (the platform admin's
 * {@code platform.chat.history-max-*} rows, else {@code app.chat.history-max-*}); a workspace's value replaces it for
 * that workspace's sessions. Tenant scope only: the history is read by session, before the turn knows its agent
 * (the same reason as {@code chat.toolLedger.*}). An operator who wants one window for everybody pins the keys
 * ({@code app.settings.locked.chat.history.maxTurns: true}). Read in {@link HistoryWindowSettings#effective()}.
 */
public class HistoryWindowCatalog implements SettingsContributor {

    private static final int SHIPPED_TURNS = 40;
    private static final int SHIPPED_CHARS = 32_000;
    private static final int SHIPPED_TOKENS = 8_000;

    private final ObjectProvider<SessionMemoryService> memory;

    /** The shipped window (40 turns, 32,000 characters, 8,000 tokens) as the platform value; for tests. */
    public HistoryWindowCatalog() {
        this(null);
    }

    public HistoryWindowCatalog(ObjectProvider<SessionMemoryService> memory) {
        this.memory = memory;
    }

    private HistoryWindowSettings.HistoryConfig platform() {
        SessionMemoryService service = memory == null ? null : memory.getIfAvailable();
        return service == null
                ? new HistoryWindowSettings.HistoryConfig(SHIPPED_TURNS, SHIPPED_CHARS, SHIPPED_TOKENS)
                : service.windowSettings.current();
    }

    private static SettingDefinition window(String key, SettingUnit unit, int min, int max, String property, IntSupplier platform) {
        return SettingDefinition.builder(key, "chat", SettingType.INT)
                .unit(unit).range(min, max).risk(SettingRisk.MEDIUM).scopes(SettingScope.TENANT)
                .property(property).docsAnchor("behaviour-settings")
                .platform(platform::getAsInt)
                .build();
    }

    @Override
    public List<SettingDefinition> definitions() {
        return List.of(
                window(HistoryWindowSettings.MAX_TURNS, SettingUnit.COUNT, 1, 400, "app.chat.history-max-turns",
                        () -> platform().maxTurns()),
                window(HistoryWindowSettings.MAX_CHARS, SettingUnit.CHARS, 0, 2_000_000, "app.chat.history-max-chars",
                        () -> platform().maxChars()),
                window(HistoryWindowSettings.MAX_TOKENS, SettingUnit.TOKENS, 0, 1_000_000, "app.chat.history-max-tokens",
                        () -> platform().maxTokens()));
    }
}
