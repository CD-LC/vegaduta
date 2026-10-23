package com.agenticai.core.prompts;

import com.agenticai.core.security.TenantContext;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A {@link PromptOverrideStore} that keeps everything in memory, for tests and hand-built fixtures
 * (it is not a bean). PLATFORM rows are global; TENANT and AGENT rows are kept per calling thread's
 * tenant, like the per-tenant schema. Not durable and not suitable for production.
 */
public class InMemoryPromptOverrideStore implements PromptOverrideStore {

    final Map<String, Map<String, PromptOverride>> rowsByTenant = new ConcurrentHashMap<>();
    final Map<String, List<PromptHistoryEntry>> historyByTenant = new ConcurrentHashMap<>();
    /** How many times the tenant's / the platform's rows were loaded (cache tests). */
    public final AtomicInteger tenantLoads = new AtomicInteger();
    public final AtomicInteger platformLoads = new AtomicInteger();
    final AtomicLong ids = new AtomicLong();
    /** When set, the load methods throw it (fail-safe tests). */
    public volatile RuntimeException failWith;

    private static final String PLATFORM = "<platform>";

    private static String bucket(PromptScope scope) {
        if (scope == PromptScope.PLATFORM) {
            return PLATFORM;
        }
        return TenantContext.current().map(TenantContext::getTenantId).orElseThrow(() -> new IllegalStateException("no tenant"));
    }

    private Map<String, PromptOverride> rows(PromptScope scope) {
        return rowsByTenant.computeIfAbsent(bucket(scope), k -> new ConcurrentHashMap<>());
    }

    private List<PromptHistoryEntry> hist(PromptScope scope) {
        return historyByTenant.computeIfAbsent(bucket(scope), k -> new java.util.concurrent.CopyOnWriteArrayList<>());
    }

    private void maybeFail() {
        if (failWith != null) {
            throw failWith;
        }
    }

    @Override
    public List<PromptOverride> loadPlatform() {
        maybeFail();
        platformLoads.incrementAndGet();
        return new ArrayList<>(rows(PromptScope.PLATFORM).values());
    }

    @Override
    public List<PromptOverride> loadCurrentTenant() {
        maybeFail();
        tenantLoads.incrementAndGet();
        return new ArrayList<>(rows(PromptScope.TENANT).values());
    }

    @Override
    public Optional<PromptOverride> find(PromptScope scope, String scopeId, String key, String locale) {
        return Optional.ofNullable(rows(scope).get(DefaultPromptResolver.rowKey(scope, scopeId, key, locale)));
    }

    @Override
    public List<PromptOverride> list(PromptScope scope, String scopeId) {
        return rows(scope).values().stream()
                .filter(r -> r.scope() == scope && r.scopeId().equals(scopeId))
                .sorted(Comparator.comparing(PromptOverride::key))
                .toList();
    }

    @Override
    public int latestVersion(PromptScope scope, String scopeId, String key, String locale) {
        return hist(scope).stream()
                .filter(h -> h.scope() == scope && h.scopeId().equals(scopeId) && h.key().equals(key) && h.locale().equals(locale))
                .mapToInt(PromptHistoryEntry::version)
                .max()
                .orElse(0);
    }

    @Override
    public void saveCurrent(PromptOverride row) {
        rows(row.scope()).put(DefaultPromptResolver.rowKey(row.scope(), row.scopeId(), row.key(), row.locale()), row);
    }

    @Override
    public void deleteCurrent(PromptScope scope, String scopeId, String key, String locale) {
        rows(scope).remove(DefaultPromptResolver.rowKey(scope, scopeId, key, locale));
    }

    @Override
    public void appendHistory(PromptHistoryEntry e) {
        hist(e.scope())
                .add(
                        new PromptHistoryEntry(
                                ids.incrementAndGet(),
                                e.scope(),
                                e.scopeId(),
                                e.key(),
                                e.locale(),
                                e.version(),
                                e.action(),
                                e.text(),
                                e.enabled(),
                                e.previousText(),
                                e.previousEnabled(),
                                e.reason(),
                                e.createdBy(),
                                e.createdAt()));
    }

    @Override
    public List<PromptHistoryEntry> history(PromptScope scope, String scopeId, String key, String locale, int limit) {
        List<PromptHistoryEntry> all = new ArrayList<>(hist(scope));
        all.removeIf(h -> h.scope() != scope || !h.scopeId().equals(scopeId) || (key != null && !h.key().equals(key)) || (locale != null && !h.locale().equals(locale)));
        all.sort(Comparator.comparingLong(PromptHistoryEntry::id).reversed());
        return all.subList(0, Math.min(limit, all.size()));
    }

    /** A row written straight in, as the migration or another replica would. */
    public void put(PromptOverride row) {
        saveCurrent(row);
    }
}
