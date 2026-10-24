package com.agenticai.core.settings;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * The least role that may change a setting at AGENT scope. Changing it at TENANT scope always needs a tenant admin.
 * {@code VIEWER} is reserved for read-only entries (none today).
 */
public enum SettingRole {
    VIEWER("VIEWER"),
    EDITOR("EDITOR"),
    TENANT_ADMIN("TENANT_ADMIN");

    private final String json;

    SettingRole(String json) {
        this.json = json;
    }

    @JsonValue
    public String json() {
        return json;
    }
}
