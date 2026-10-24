package com.agenticai.core.settings;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.time.Instant;

/**
 * One immutable history row. {@code action} is {@code SET} or {@code RESET}; {@code value} is null after a reset,
 * {@code previousValue} null when nothing was overridden before. JSON: {@code version, value, previousValue, actor,
 * at, reason} plus {@code action}.
 */
public record SettingHistoryEntry(
        @JsonIgnore long id,
        @JsonIgnore SettingScope scope,
        @JsonIgnore String scopeId,
        @JsonIgnore String key,
        int version,
        String action,
        Object value,
        Object previousValue,
        String reason,
        String actor,
        Instant at) {

    public static final String ACTION_SET = "SET";
    public static final String ACTION_RESET = "RESET";
}
