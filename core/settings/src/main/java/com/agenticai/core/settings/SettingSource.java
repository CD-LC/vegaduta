package com.agenticai.core.settings;

import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

/**
 * Which layer a value came from: {@link #DEFAULT} (nothing configured anywhere, the code default), {@link #PLATFORM}
 * (the deployment's yml or environment), {@link #TENANT} or {@link #AGENT}.
 */
public enum SettingSource {
    DEFAULT,
    PLATFORM,
    TENANT,
    AGENT;

    @JsonValue
    public String json() {
        return name().toLowerCase(Locale.ROOT);
    }
}
