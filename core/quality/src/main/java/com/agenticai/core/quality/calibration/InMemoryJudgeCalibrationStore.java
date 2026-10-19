package com.agenticai.core.quality.calibration;

import com.agenticai.core.security.TenantContext;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-JVM store, partitioned by tenant. It is the fallback {@link RedisJudgeCalibrationStore} uses when no Redis
 * is configured, and the store unit tests use. Contents are lost on restart and are not shared between replicas.
 */
public class InMemoryJudgeCalibrationStore implements JudgeCalibrationStore {

    /** Cap on retained results per tenant; the oldest are dropped first. */
    static final int MAX_RESULTS = 500;

    private static final class Tenant {
        final Map<UUID, JudgeCalibrationSet> sets = new ConcurrentHashMap<>();
        final Map<UUID, Map<UUID, JudgeCalibrationItem>> items = new ConcurrentHashMap<>();
        final List<JudgeCalibrationResult> results = new ArrayList<>();
    }

    private final Map<String, Tenant> tenants = new ConcurrentHashMap<>();

    static String tenantKey() {
        return TenantContext.current().map(TenantContext::getTenantId).orElse("default");
    }

    private Tenant tenant() {
        return tenants.computeIfAbsent(tenantKey(), k -> new Tenant());
    }

    @Override
    public JudgeCalibrationSet saveSet(JudgeCalibrationSet set) {
        tenant().sets.put(set.getId(), set);
        return set;
    }

    @Override
    public Optional<JudgeCalibrationSet> findSet(UUID setId) {
        return Optional.ofNullable(tenant().sets.get(setId));
    }

    @Override
    public List<JudgeCalibrationSet> listSets(UUID agentId) {
        return tenant().sets.values().stream()
            .filter(s -> s.getAgentId().equals(agentId))
            .sorted(Comparator.comparing(JudgeCalibrationSet::getCreatedAt).reversed())
            .toList();
    }

    @Override
    public void deleteSet(UUID setId) {
        Tenant t = tenant();
        t.sets.remove(setId);
        t.items.remove(setId);
        synchronized (t.results) {
            t.results.removeIf(r -> r.getSetId().equals(setId));
        }
    }

    @Override
    public List<JudgeCalibrationItem> saveItems(List<JudgeCalibrationItem> items) {
        Tenant t = tenant();
        for (JudgeCalibrationItem item : items) {
            t.items.computeIfAbsent(item.getSetId(), k -> new ConcurrentHashMap<>()).put(item.getId(), item);
        }
        return items;
    }

    @Override
    public List<JudgeCalibrationItem> listItems(UUID setId) {
        return tenant().items.getOrDefault(setId, Map.of()).values().stream()
            .sorted(Comparator.comparingInt(JudgeCalibrationItem::getPosition)
                .thenComparing(JudgeCalibrationItem::getCreatedAt))
            .toList();
    }

    @Override
    public long countItems(UUID setId) {
        return tenant().items.getOrDefault(setId, Map.of()).size();
    }

    @Override
    public Optional<JudgeCalibrationItem> findItem(UUID setId, UUID itemId) {
        return Optional.ofNullable(tenant().items.getOrDefault(setId, Map.of()).get(itemId));
    }

    @Override
    public void deleteItem(UUID setId, UUID itemId) {
        Map<UUID, JudgeCalibrationItem> m = tenant().items.get(setId);
        if (m != null) {
            m.remove(itemId);
        }
    }

    @Override
    public JudgeCalibrationResult saveResult(JudgeCalibrationResult result) {
        Tenant t = tenant();
        synchronized (t.results) {
            t.results.add(result);
            t.results.sort(Comparator.comparing(JudgeCalibrationResult::getCreatedAt));
            while (t.results.size() > MAX_RESULTS) {
                t.results.remove(0);
            }
        }
        return result;
    }

    private List<JudgeCalibrationResult> newestFirst(java.util.function.Predicate<JudgeCalibrationResult> filter,
                                                     int limit) {
        Tenant t = tenant();
        List<JudgeCalibrationResult> copy;
        synchronized (t.results) {
            copy = new ArrayList<>(t.results);
        }
        return copy.stream().filter(filter)
            .sorted(Comparator.comparing(JudgeCalibrationResult::getCreatedAt).reversed())
            .limit(Math.max(0, limit)).toList();
    }

    @Override
    public List<JudgeCalibrationResult> resultsForAgent(UUID agentId, int limit) {
        return newestFirst(r -> r.getAgentId().equals(agentId), limit);
    }

    @Override
    public List<JudgeCalibrationResult> resultsForSet(UUID setId, int limit) {
        return newestFirst(r -> r.getSetId().equals(setId), limit);
    }

    @Override
    public Optional<JudgeCalibrationResult> latestForJudgeModel(String judgeModel) {
        return newestFirst(r -> judgeModel != null && judgeModel.equals(r.getJudgeModel()), 1).stream().findFirst();
    }
}
