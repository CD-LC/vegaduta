package com.agenticai.core.settings;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Persistence of overrides and their history, in the CURRENT tenant's schema (the calling thread's tenant), so a
 * store can only ever show one tenant's rows. The history is append-only (a database trigger refuses UPDATE and
 * DELETE); there is deliberately no method that changes or removes a history row.
 */
public interface SettingsStore {

    /** The tenant-scope overrides of the current tenant, key to value (JSON-parsed). */
    Map<String, Object> tenantValues();

    /** One agent's overrides, key to value. */
    Map<String, Object> agentValues(String agentId);

    Optional<SettingOverride> find(SettingScope scope, String scopeId, String key);

    /** The highest version ever recorded for the combination (0 if none). */
    int latestVersion(SettingScope scope, String scopeId, String key);

    /** Inserts or replaces the current row. */
    void saveCurrent(SettingOverride row);

    /** Removes the current row (the history stays). */
    void deleteCurrent(SettingScope scope, String scopeId, String key);

    void appendHistory(SettingHistoryEntry entry);

    /** Newest first. */
    List<SettingHistoryEntry> history(SettingScope scope, String scopeId, String key, int limit);

    /**
     * One page of the history of EVERY key, newest first ({@code at} descending, then the newest id). {@code scope}
     * null = both scopes; {@code scopeId} null = every scope id of the chosen scope(s) (all agents), otherwise exactly
     * that id; {@code since} null = no lower bound, else rows at or after it.
     */
    List<SettingHistoryEntry> historyPage(SettingScope scope, String scopeId, Instant since, int limit, int offset);

    /** How many rows {@link #historyPage} filters to, before paging. */
    long historyCount(SettingScope scope, String scopeId, Instant since);
}
