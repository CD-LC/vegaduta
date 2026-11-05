package com.agenticai.core.agents.memorystore;

import com.agenticai.core.agents.memory.spi.VerifiedOutcome;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Test double with the same observable semantics as {@link JdbcCompetenceStorage} (counting, cap,
 * overflow).
 */
final class InMemoryCompetenceStorage implements CompetenceStorage {

    private final Map<UUID, Map<String, int[]>> byAgent = new LinkedHashMap<>();
    private final Map<UUID, Map<String, Instant>> last = new LinkedHashMap<>();
    boolean failing;

    @Override
    public synchronized void add(VerifiedOutcome outcome, int maxClasses, Instant now) {
        if (failing) {
            throw new IllegalStateException("db down");
        }
        Map<String, int[]> rows =
                byAgent.computeIfAbsent(outcome.agentId(), k -> new LinkedHashMap<>());
        String cls = outcome.taskClass();
        long distinct = rows.keySet().stream().filter(c -> !OVERFLOW_CLASS.equals(c)).count();
        if (!rows.containsKey(cls) && distinct >= maxClasses) {
            cls = OVERFLOW_CLASS;
        }
        int[] c = rows.computeIfAbsent(cls, k -> new int[6]);
        int[] d = JdbcCompetenceStorage.deltas(outcome.signal());
        c[0]++;
        for (int i = 0; i < 5; i++) {
            c[i + 1] += d[i];
        }
        last.computeIfAbsent(outcome.agentId(), k -> new LinkedHashMap<>()).put(cls, now);
    }

    @Override
    public synchronized List<CompetenceRow> rows(UUID agentId) {
        if (failing) {
            throw new IllegalStateException("db down");
        }
        List<CompetenceRow> out = new ArrayList<>();
        byAgent.getOrDefault(agentId, Map.of())
                .forEach(
                        (cls, c) ->
                                out.add(
                                        new CompetenceRow(
                                                cls,
                                                c[0],
                                                c[1],
                                                c[2],
                                                c[3],
                                                c[4],
                                                c[5],
                                                last.get(agentId).get(cls))));
        out.sort(
                Comparator.comparingInt(CompetenceRow::attempts)
                        .reversed()
                        .thenComparing(CompetenceRow::taskClass));
        return out;
    }
}
