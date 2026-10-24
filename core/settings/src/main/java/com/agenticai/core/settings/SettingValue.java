package com.agenticai.core.settings;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * A resolved value and where it came from. {@code locked}: an operator pinned the key ({@code app.settings.locked}),
 * so it is the platform value whatever is stored. {@code inherited}: what a reset of the override at the context's
 * own layer would give (present only when the value comes from a tenant or agent override).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SettingValue(
        Object value,
        SettingSource source,
        boolean locked,
        String lockedReason,
        Inherited inherited) {

    /** The value one layer down. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Inherited(Object value, SettingSource source) {}

    public SettingValue(Object value, SettingSource source, boolean locked, String lockedReason) {
        this(value, source, locked, lockedReason, null);
    }
}
