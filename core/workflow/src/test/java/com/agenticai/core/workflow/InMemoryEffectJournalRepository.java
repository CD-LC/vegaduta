package com.agenticai.core.workflow;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * In-memory stand-in for {@link WorkflowEffectJournalRepository}'s custom statements with the same
 * atomic semantics as the native SQL (insert-on-conflict-do-nothing, status-guarded updates). The
 * SQL itself is exercised against a real Postgres separately; this keeps the protocol tests fast.
 */
final class InMemoryEffectJournalRepository {

    final Map<String, WorkflowEffectJournalEntry> rows = new LinkedHashMap<>();
    /** When set, {@code complete} throws - simulates a crash right after the effect ran. */
    boolean failComplete;

    WorkflowEffectJournalRepository proxy() {
        return (WorkflowEffectJournalRepository)
                Proxy.newProxyInstance(
                        getClass().getClassLoader(),
                        new Class<?>[] {WorkflowEffectJournalRepository.class},
                        (p, m, a) -> {
                            switch (m.getName()) {
                                case "claim":
                                    return claim((UUID) a[0], (String) a[1], (String) a[2], (String) a[3], (String) a[4]);
                                case "takeOver":
                                    return takeOver((UUID) a[0], (String) a[1], (String) a[2], castList(a[3]));
                                case "complete":
                                    if (failComplete) {
                                        throw new IllegalStateException("simulated crash before COMPLETED");
                                    }
                                    return complete(a);
                                case "transition":
                                    return transition((UUID) a[0], (String) a[1], (String) a[2], (String) a[3]);
                                case "markStartedUncertain":
                                    return markStartedUncertain((UUID) a[0]);
                                case "findByRunIdAndNodeIdAndIterationKey":
                                    return Optional.ofNullable(rows.get(key((UUID) a[0], (String) a[1], (String) a[2])));
                                case "findByRunIdAndStatusInOrderByStartedAtAsc":
                                    List<String> statuses = castList(a[1]);
                                    List<WorkflowEffectJournalEntry> out = new ArrayList<>();
                                    for (WorkflowEffectJournalEntry e : rows.values()) {
                                        if (e.getRunId().equals(a[0]) && statuses.contains(e.getStatus())) {
                                            out.add(e);
                                        }
                                    }
                                    return out;
                                case "purgeStartedBefore":
                                    return 0;
                                case "toString":
                                    return "InMemoryEffectJournalRepository";
                                case "hashCode":
                                    return System.identityHashCode(p);
                                case "equals":
                                    return p == a[0];
                                default:
                                    throw new UnsupportedOperationException(m.getName());
                            }
                        });
    }

    @SuppressWarnings("unchecked")
    private static List<String> castList(Object o) {
        return (List<String>) o;
    }

    private static String key(UUID run, String node, String iteration) {
        return run + "|" + node + "|" + iteration;
    }

    private synchronized int claim(UUID run, String node, String type, String iteration, String idem) {
        String k = key(run, node, iteration);
        if (rows.containsKey(k)) {
            return 0;
        }
        try {
            Constructor<WorkflowEffectJournalEntry> c = WorkflowEffectJournalEntry.class.getDeclaredConstructor();
            c.setAccessible(true);
            WorkflowEffectJournalEntry e = c.newInstance();
            set(e, "id", UUID.randomUUID());
            set(e, "runId", run);
            set(e, "nodeId", node);
            set(e, "nodeType", type);
            set(e, "iterationKey", iteration);
            set(e, "idempotencyKey", idem);
            set(e, "status", WorkflowEffectJournalEntry.STARTED);
            set(e, "attemptCount", 1);
            set(e, "startedAt", Instant.now());
            rows.put(k, e);
            return 1;
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private synchronized int takeOver(UUID run, String node, String iteration, List<String> from) {
        WorkflowEffectJournalEntry e = rows.get(key(run, node, iteration));
        if (e == null || !from.contains(e.getStatus())) {
            return 0;
        }
        set(e, "status", WorkflowEffectJournalEntry.STARTED);
        set(e, "attemptCount", e.getAttemptCount() + 1);
        set(e, "startedAt", Instant.now());
        set(e, "completedAt", null);
        return 1;
    }

    private synchronized int complete(Object[] a) {
        WorkflowEffectJournalEntry e = rows.get(key((UUID) a[0], (String) a[1], (String) a[2]));
        if (e == null
                || !(WorkflowEffectJournalEntry.STARTED.equals(e.getStatus())
                        || WorkflowEffectJournalEntry.UNCERTAIN.equals(e.getStatus()))) {
            return 0;
        }
        set(e, "status", WorkflowEffectJournalEntry.COMPLETED);
        set(e, "completedAt", Instant.now());
        set(e, "resultStatus", a[3]);
        set(e, "resultOutput", a[4]);
        set(e, "resultOutputTruncated", a[5]);
        set(e, "resultDigest", a[6]);
        set(e, "resultBranch", a[7]);
        set(e, "resultSummary", a[8]);
        set(e, "resultFileName", a[9]);
        return 1;
    }

    private synchronized int transition(UUID run, String node, String iteration, String to) {
        WorkflowEffectJournalEntry e = rows.get(key(run, node, iteration));
        if (e == null
                || !(WorkflowEffectJournalEntry.STARTED.equals(e.getStatus())
                        || WorkflowEffectJournalEntry.UNCERTAIN.equals(e.getStatus()))) {
            return 0;
        }
        set(e, "status", to);
        return 1;
    }

    private synchronized int markStartedUncertain(UUID run) {
        int n = 0;
        for (WorkflowEffectJournalEntry e : rows.values()) {
            if (e.getRunId().equals(run) && WorkflowEffectJournalEntry.STARTED.equals(e.getStatus())) {
                set(e, "status", WorkflowEffectJournalEntry.UNCERTAIN);
                n++;
            }
        }
        return n;
    }

    private static void set(WorkflowEffectJournalEntry e, String field, Object value) {
        try {
            Field f = WorkflowEffectJournalEntry.class.getDeclaredField(field);
            f.setAccessible(true);
            f.set(e, value);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /** Simulates a crash: a STARTED row exists but no completion was ever recorded. */
    void seedStarted(UUID run, String node, String type, String iteration) {
        claim(run, node, type, iteration, EffectIdempotency.keyFor(run.toString(), node, iteration));
    }
}
