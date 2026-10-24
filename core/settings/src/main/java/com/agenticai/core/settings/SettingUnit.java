package com.agenticai.core.settings;

import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

/** The unit a numeric value is expressed in; shown next to the input. JSON name is lower-case. */
public enum SettingUnit {
    CHARS,
    TOKENS,
    MS,
    S,
    COUNT,
    USD,
    PERCENT;

    @JsonValue
    public String json() {
        return name().toLowerCase(Locale.ROOT);
    }
}
