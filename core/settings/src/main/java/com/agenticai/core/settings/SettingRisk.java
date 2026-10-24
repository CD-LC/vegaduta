package com.agenticai.core.settings;

import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

/**
 * What a wrong value can cost. {@link #HIGH} and {@link #SECURITY} need a typed reason on every change (and the UI a
 * confirmation that explains the consequence); {@link #SECURITY} settings can additionally only be made stricter than
 * the platform value and need a tenant admin.
 */
public enum SettingRisk {
    LOW,
    MEDIUM,
    HIGH,
    SECURITY;

    @JsonValue
    public String json() {
        return name().toLowerCase(Locale.ROOT);
    }

    public boolean requiresReason() {
        return this == HIGH || this == SECURITY;
    }
}
