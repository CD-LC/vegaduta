package com.agenticai.core.agents.turn;

import java.time.LocalDate;
import java.util.Map;

/**
 * One tenant's prompt-directive overrides as seen by one turn (see {@link PromptDirectiveSettings}).
 * Immutable and cheap: the assembler resolves it once per turn and asks it per directive.
 * {@link #DEFAULTS} (no overrides) returns every default block unchanged.
 */
public final class ResolvedDirectives {

    /** A tenant's stored setting for one directive. */
    public record Setting(boolean enabled, String textOverride) {}

    /** No overrides: every block passes through unchanged, no custom instructions. */
    public static final ResolvedDirectives DEFAULTS = new ResolvedDirectives(Map.of(), null);

    private final Map<String, Setting> settings;
    private final LocalDate today;

    ResolvedDirectives(Map<String, Setting> settings, LocalDate today) {
        this.settings = settings;
        this.today = today;
    }

    /**
     * The block to send for {@code key}: {@code null} when the owner switched it off (or the
     * platform has nothing to send this turn, {@code defaultBlock == null}); the owner's text when
     * one is set; otherwise {@code defaultBlock}, the text the platform computed for this turn.
     */
    public String resolve(String key, String defaultBlock) {
        if (defaultBlock == null) {
            return null;
        }
        Setting setting = settings.get(key);
        if (setting == null) {
            return defaultBlock;
        }
        if (!setting.enabled()) {
            return null;
        }
        String text = setting.textOverride();
        if (text == null || text.isBlank()) {
            return defaultBlock;
        }
        return PromptDirectiveRegistry.CURRENT_DATE.equals(key) && today != null
                ? text.replace("{date}", today.toString())
                : text;
    }

    /** The owner's enabled free-text block appended after the built-ins, or {@code null}. */
    public String customInstructionsBlock() {
        Setting setting = settings.get(PromptDirectiveRegistry.CUSTOM_INSTRUCTIONS_KEY);
        if (setting == null || !setting.enabled()) {
            return null;
        }
        String text = setting.textOverride();
        return text == null || text.isBlank() ? null : text.strip();
    }
}
