package com.agenticai.core.settings;

import com.agenticai.core.security.TenantContext;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A {@link SettingsStore} in memory, for tests of this and of consuming modules. It keeps the tenancy property of the
 * real store: rows are partitioned by the calling thread's tenant id, so one tenant never sees another's. History is
 * append-only here too. Not registered as a bean.
 */
public class InMemorySettingsStore implements SettingsStore {

    private final Map<String, SettingOverride> current = new ConcurrentHashMap<>();
    private final List<Row> history = new java.util.concurrent.CopyOnWriteArrayList<>();

    private record Row(String tenant, SettingHistoryEntry entry) {}

    private static String tenant() {
        return TenantContext.current().map(TenantContext::getTenantId).orElse("<none>");
    }

    private static String k(SettingScope scope, String scopeId, String key) {
        return tenant() + "|" + scope.json() + "|" + scopeId + "|" + key;
    }

    private Map<String, Object> values(SettingScope scope, String scopeId) {
        String prefix = tenant() + "|" + scope.json() + "|" + scopeId + "|";
        Map<String, Object> out = new LinkedHashMap<>();
        current.forEach((k, v) -> {
            if (k.startsWith(prefix)) {
                out.put(v.key(), v.value());
            }
        });
        return out;
    }

    @Override
    public Map<String, Object> tenantValues() {
        return values(SettingScope.TENANT, "");
    }

    @Override
    public Map<String, Object> agentValues(String agentId) {
        return values(SettingScope.AGENT, agentId);
    }

    @Override
    public Optional<SettingOverride> find(SettingScope scope, String scopeId, String key) {
        return Optional.ofNullable(current.get(k(scope, scopeId, key)));
    }

    @Override
    public int latestVersion(SettingScope scope, String scopeId, String key) {
        String t = tenant();
        return history.stream()
                .filter(r -> r.tenant().equals(t))
                .map(Row::entry)
                .filter(e -> e.scope() == scope && e.scopeId().equals(scopeId) && e.key().equals(key))
                .mapToInt(SettingHistoryEntry::version)
                .max()
                .orElse(0);
    }

    @Override
    public void saveCurrent(SettingOverride row) {
        current.put(k(row.scope(), row.scopeId(), row.key()), row);
    }

    @Override
    public void deleteCurrent(SettingScope scope, String scopeId, String key) {
        current.remove(k(scope, scopeId, key));
    }

    @Override
    public void appendHistory(SettingHistoryEntry entry) {
        // The real table has UNIQUE (scope, scope_id, setting_key, version): two writers that computed the same next
        // version must not both succeed. Check-and-add under one lock so the second one gets the same refusal.
        synchronized (history) {
            String t = tenant();
            for (Row r : history) {
                SettingHistoryEntry e = r.entry();
                if (r.tenant().equals(t) && e.scope() == entry.scope() && e.scopeId().equals(entry.scopeId())
                        && e.key().equals(entry.key()) && e.version() == entry.version()) {
                    throw new org.springframework.dao.DuplicateKeyException(
                            "setting_override_history_version_uq: " + entry.key() + " v" + entry.version());
                }
            }
            history.add(new Row(t, entry));
        }
    }

    @Override
    public List<SettingHistoryEntry> historyPage(SettingScope scope, String scopeId, java.time.Instant since, int limit, int offset) {
        List<SettingHistoryEntry> all = filtered(scope, scopeId, since);
        int from = Math.min(Math.max(0, offset), all.size());
        int to = Math.min(all.size(), from + Math.max(1, limit));
        return new ArrayList<>(all.subList(from, to));
    }

    @Override
    public long historyCount(SettingScope scope, String scopeId, java.time.Instant since) {
        return filtered(scope, scopeId, since).size();
    }

    /** Newest first: by time, then by insertion order (the id of the real table). */
    private List<SettingHistoryEntry> filtered(SettingScope scope, String scopeId, java.time.Instant since) {
        String t = tenant();
        List<SettingHistoryEntry> out = new ArrayList<>();
        for (Row r : history) {
            SettingHistoryEntry e = r.entry();
            if (r.tenant().equals(t)
                    && (scope == null || e.scope() == scope)
                    && (scopeId == null || e.scopeId().equals(scopeId))
                    && (since == null || !e.at().isBefore(since))) {
                out.add(e);
            }
        }
        java.util.Collections.reverse(out); // insertion order, newest first
        out.sort(Comparator.comparing(SettingHistoryEntry::at).reversed()); // stable: equal times keep newest-inserted first
        return out;
    }

    @Override
    public List<SettingHistoryEntry> history(SettingScope scope, String scopeId, String key, int limit) {
        String t = tenant();
        List<SettingHistoryEntry> out = new ArrayList<>();
        for (Row r : history) {
            SettingHistoryEntry e = r.entry();
            if (r.tenant().equals(t) && e.scope() == scope && e.scopeId().equals(scopeId) && e.key().equals(key)) {
                out.add(e);
            }
        }
        out.sort(Comparator.comparingInt(SettingHistoryEntry::version).reversed());
        return out.size() > limit ? new ArrayList<>(out.subList(0, limit)) : out;
    }
}
