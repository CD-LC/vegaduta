package com.agenticai.core.settings;

import java.time.Instant;

/** One stored override: the current value of a key at one scope. {@code scopeId} is empty for TENANT, the agent id for AGENT. */
public record SettingOverride(
        SettingScope scope, String scopeId, String key, Object value, int version, String updatedBy, Instant updatedAt) {}
