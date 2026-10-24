package com.agenticai.core.settings;

import java.time.Instant;

/**
 * One row of the all-keys history ({@code GET /api/settings/history}, {@code GET /api/agents/{id}/settings/history}):
 * the same fields as {@link SettingHistoryEntry} plus the key and where it was changed. {@code agentId} is null for a
 * workspace-level change; value and previousValue are null after a reset / when nothing was overridden before.
 */
public record SettingHistoryRow(
        String key,
        SettingScope scope,
        String agentId,
        int version,
        String action,
        Object value,
        Object previousValue,
        String reason,
        String actor,
        Instant at) {

    static SettingHistoryRow of(SettingHistoryEntry e) {
        return new SettingHistoryRow(
                e.key(),
                e.scope(),
                e.scope() == SettingScope.AGENT ? e.scopeId() : null,
                e.version(),
                e.action(),
                e.value(),
                e.previousValue(),
                e.reason(),
                e.actor(),
                e.at());
    }
}
