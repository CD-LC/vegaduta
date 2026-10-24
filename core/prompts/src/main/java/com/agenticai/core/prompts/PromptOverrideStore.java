package com.agenticai.core.prompts;

import java.util.List;
import java.util.Optional;

/**
 * Persistence of overrides and their history. PLATFORM rows live in the control plane, TENANT and
 * AGENT rows in the current tenant's schema (the calling thread's tenant), so a store can only ever
 * show one tenant's rows. The history is append-only (a database trigger refuses UPDATE and
 * DELETE); there is deliberately no method that changes or removes a history row.
 */
public interface PromptOverrideStore {

    /** Every PLATFORM row. */
    List<PromptOverride> loadPlatform();

    /** Every TENANT and AGENT row of the current tenant. */
    List<PromptOverride> loadCurrentTenant();

    Optional<PromptOverride> find(PromptScope scope, String scopeId, String key, String locale);

    /** The rows at one scope (all keys); {@code scopeId} is empty for PLATFORM and TENANT. */
    List<PromptOverride> list(PromptScope scope, String scopeId);

    /** The highest version ever recorded for the combination (0 if none). */
    int latestVersion(PromptScope scope, String scopeId, String key, String locale);

    /** Inserts or replaces the current row. */
    void saveCurrent(PromptOverride row);

    /** Removes the current row (the history stays). */
    void deleteCurrent(PromptScope scope, String scopeId, String key, String locale);

    /** Appends one history row. */
    void appendHistory(PromptHistoryEntry entry);

    /**
     * History of a scope, newest first; {@code key} and {@code locale} may be null to widen it.
     */
    List<PromptHistoryEntry> history(PromptScope scope, String scopeId, String key, String locale, int limit);
}
