package com.agenticai.core.settings;

import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;
import java.util.Optional;

/** Where an override is stored: the tenant (every agent of it) or one agent. */
public enum SettingScope {
    TENANT,
    AGENT;

    @JsonValue
    public String json() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Optional<SettingScope> parse(String value) {
        if (value == null) {
            return Optional.empty();
        }
        for (SettingScope s : values()) {
            if (s.json().equals(value.strip().toLowerCase(Locale.ROOT))) {
                return Optional.of(s);
            }
        }
        return Optional.empty();
    }
}
