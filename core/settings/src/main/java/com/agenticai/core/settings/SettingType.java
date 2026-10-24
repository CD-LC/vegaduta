package com.agenticai.core.settings;

import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

/**
 * How a setting's value is shaped and validated. The JSON name is the lower-case form.
 *
 * <p>Canonical Java representation of a value (what {@link SettingValue#value()} holds): {@link #INT}, {@link #LONG}
 * and {@link #DURATION} are {@link Long} (a duration is a whole number of the entry's unit, {@code ms} or {@code s});
 * {@link #DECIMAL} and {@link #PERCENT} are {@link Double}; {@link #BOOL} is {@link Boolean}; {@link #ENUM},
 * {@link #STRING} and {@link #TEXT} are {@link String}.
 */
public enum SettingType {
    INT,
    LONG,
    DECIMAL,
    DURATION,
    BOOL,
    ENUM,
    STRING,
    TEXT,
    PERCENT;

    @JsonValue
    public String json() {
        return name().toLowerCase(Locale.ROOT);
    }

    public boolean isIntegral() {
        return this == INT || this == LONG || this == DURATION;
    }

    public boolean isNumeric() {
        return isIntegral() || this == DECIMAL || this == PERCENT;
    }

    public boolean isText() {
        return this == STRING || this == TEXT || this == ENUM;
    }
}
