package com.agenticai.core.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.agenticai.core.workflow.WorkflowNodeExecutor.NodeExecutionResult;
import com.agenticai.core.workflow.dto.WorkflowNodeDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The effect journal protocol (V680), driven through the real {@link WorkflowNodeExecutor} so the
 * wiring (journal around the dispatch, after the approval gate and expression resolution) is under
 * test too: a duplicate execution runs the effect once, a STARTED-only row is "effect uncertain",
 * and loop / parallel executions get distinct keys.
 */
class WorkflowEffectJournalTest {

    private static final String RUN = UUID.randomUUID().toString();

    private InMemoryEffectJournalRepository store;
    private final AtomicInteger sends = new AtomicInteger();
    private final List<String> seenKeys = new ArrayList<>();
    private RuntimeException failNext;

    @BeforeEach
    void setUp() {
        store = new InMemoryEffectJournalRepository();
    }

    @AfterEach
    void clear() {
        ExpressionScopeContext.clear();
    }

    private WorkflowNodeExecutor executor(boolean enabled, String onUncertain) {
        NodeHandler output =
                new NodeHandler() {
                    @Override
                    public Set<String> nodeTypes() {
                        return Set.of("output", "http", "condition");
                    }

                    @Override
                    public NodeExecutionResult handle(NodeInvocation inv) {
                        sends.incrementAndGet();
                        seenKeys.add(EffectIdempotency.current());
                        if (failNext != null) {
                            RuntimeException f = failNext;
                            failNext = null;
                            throw f;
                        }
                        return NodeExecutionResult.ok(
                                "sent#" + sends.get(), "Sent.", "main", List.of("sent"));
                    }
                };
        WorkflowNodeExecutor ex =
                new WorkflowNodeExecutor(mock(OrchestratorService.class), new ObjectMapper(), List.of(output));
        ex.setEffectJournal(
                new WorkflowEffectJournal(store.proxy(), enabled, onUncertain, 200_000));
        return ex;
    }

    private static WorkflowNodeDto node(String id, String type, Map<String, Object> config) {
        return new WorkflowNodeDto(id, type, Map.of(), Map.of("label", id + "-label", "config", config));
    }

    private NodeExecutionResult run(WorkflowNodeExecutor ex, WorkflowNodeDto n, Integer iteration) {
        if (iteration != null) {
            ExpressionScopeContext.bind(ExpressionScope.iteration("item" + iteration, iteration, List.of("a", "b", "c")));
        }
        try {
            return ex.executeNode("wf", "WF", RUN, n, "in", "u");
        } finally {
            ExpressionScopeContext.clear();
        }
    }

    @Test
    void duplicateExecutionRunsTheEffectOnceAndReplaysTheRecordedResult() {
        WorkflowNodeExecutor ex = executor(true, "fail");
        WorkflowNodeDto n = node("out1", "output", Map.of());

        NodeExecutionResult first = run(ex, n, null);
        NodeExecutionResult second = run(ex, n, null);

        assertEquals(1, sends.get(), "the side effect must run exactly once");
        assertEquals("sent#1", first.output());
        assertEquals("sent#1", second.output(), "the recorded output is replayed");
        assertEquals("main", second.branch());
        assertTrue(second.summary().contains("replayed from effect journal"));
        assertTrue(second.logLines().get(0).contains("side effect NOT re-run"));
        assertEquals(1, store.rows.size());
        assertEquals(WorkflowEffectJournalEntry.COMPLETED, store.rows.values().iterator().next().getStatus());
        assertNotNull(store.rows.values().iterator().next().getResultDigest());
    }

    @Test
    void theHandlerSeesTheStableIdempotencyKey() {
        WorkflowNodeExecutor ex = executor(true, "fail");
        run(ex, node("out1", "output", Map.of()), 2);
        assertEquals(List.of(RUN + ":out1:2"), seenKeys);
        assertNull(EffectIdempotency.current(), "the key must not leak past the effect");
    }

    @Test
    void aStartedRowWithoutCompletionIsEffectUncertainAndIsNotReRun() {
        WorkflowNodeExecutor ex = executor(true, "fail");
        store.seedStarted(UUID.fromString(RUN), "out1", "output", "-"); // crash between call and COMPLETED

        NodeExecutionResult r = run(ex, node("out1", "output", Map.of()), null);

        assertEquals(0, sends.get(), "an uncertain effect must never be silently re-run");
        assertEquals(WorkflowRunStatus.FAILED, r.haltRun());
        assertTrue(r.summary().contains("Effect uncertain"));
        assertTrue(r.summary().contains("out1-label"));
        assertEquals(
                WorkflowEffectJournalEntry.UNCERTAIN,
                store.rows.values().iterator().next().getStatus());
    }

    @Test
    void rerunSwitchTakesTheUncertainKeyOverAndSaysSo() {
        WorkflowNodeExecutor ex = executor(true, "rerun");
        store.seedStarted(UUID.fromString(RUN), "out1", "output", "-");

        NodeExecutionResult r = run(ex, node("out1", "output", Map.of()), null);

        assertEquals(1, sends.get());
        assertNull(r.haltRun());
        assertTrue(r.logLines().stream().anyMatch(l -> l.contains("on-uncertain=rerun")));
        WorkflowEffectJournalEntry row = store.rows.values().iterator().next();
        assertEquals(WorkflowEffectJournalEntry.COMPLETED, row.getStatus());
        assertEquals(2, row.getAttemptCount());
    }

    @Test
    void aFailedAttemptReleasesTheKeySoALegitimateRetryRuns() {
        WorkflowNodeExecutor ex = executor(true, "fail");
        WorkflowNodeDto n = node("out1", "output", Map.of());
        failNext = new IllegalStateException("smtp down");

        assertThrows(IllegalStateException.class, () -> run(ex, n, null));
        assertEquals(WorkflowEffectJournalEntry.FAILED, store.rows.values().iterator().next().getStatus());

        NodeExecutionResult retry = run(ex, n, null);
        assertEquals("sent#2", retry.output());
        assertEquals(2, sends.get());
        assertEquals(2, store.rows.values().iterator().next().getAttemptCount());
        // ...and a third execution now replays instead of sending again
        run(ex, n, null);
        assertEquals(2, sends.get());
    }

    @Test
    void ifTheCompletionIsNeverRecordedTheNextExecutionIsUncertainNotADuplicate() {
        WorkflowNodeExecutor ex = executor(true, "fail");
        WorkflowNodeDto n = node("out1", "output", Map.of());
        store.failComplete = true;

        NodeExecutionResult first = run(ex, n, null);
        assertEquals("sent#1", first.output(), "bookkeeping failure must not fail the node");

        store.failComplete = false;
        NodeExecutionResult second = run(ex, n, null);
        assertEquals(1, sends.get());
        assertEquals(WorkflowRunStatus.FAILED, second.haltRun());
    }

    @Test
    void loopIterationsGetDistinctKeysAndEachRunsOnce() {
        WorkflowNodeExecutor ex = executor(true, "fail");
        WorkflowNodeDto n = node("out1", "output", Map.of());

        for (int pass = 0; pass < 2; pass++) { // second pass = a retry of the whole loop
            for (int i = 0; i < 3; i++) {
                run(ex, n, i);
            }
        }

        assertEquals(3, sends.get(), "one send per iteration, none repeated on the second pass");
        assertEquals(3, store.rows.size());
        assertEquals(List.of(RUN + ":out1:0", RUN + ":out1:1", RUN + ":out1:2"), seenKeys);
    }

    @Test
    void parallelBranchesAreDistinctNodesWithDistinctKeys() {
        WorkflowNodeExecutor ex = executor(true, "fail");
        run(ex, node("branchA", "output", Map.of()), null);
        run(ex, node("branchB", "output", Map.of()), null);
        run(ex, node("branchA", "output", Map.of()), null);

        assertEquals(2, sends.get());
        assertEquals(List.of(RUN + ":branchA:-", RUN + ":branchB:-"), seenKeys);
    }

    @Test
    void onlyIrreversibleNodesAreJournaled() {
        WorkflowNodeExecutor ex = executor(true, "fail");
        run(ex, node("c", "condition", Map.of()), null);
        run(ex, node("g", "http", Map.of("method", "GET")), null);
        run(ex, node("g2", "http", Map.of()), null);
        assertTrue(store.rows.isEmpty());

        run(ex, node("p", "http", Map.of("method", "post")), null);
        assertEquals(1, store.rows.size());
        assertTrue(WorkflowEffectJournal.isIrreversible(node("x", "bulk_email", Map.of())));
        assertTrue(WorkflowEffectJournal.isIrreversible(node("x", "tool", Map.of())));
        assertTrue(WorkflowEffectJournal.isIrreversible(node("x", "crm_lead", Map.of())));
        assertTrue(WorkflowEffectJournal.isIrreversible(node("x", "device_control", Map.of())));
        assertTrue(WorkflowEffectJournal.isIrreversible(node("x", "media", Map.of())));
        assertTrue(WorkflowEffectJournal.isIrreversible(node("x", "workflow", Map.of())));
        assertFalse(WorkflowEffectJournal.isIrreversible(node("x", "agent", Map.of())));
    }

    @Test
    void disabledJournalChangesNothing() {
        WorkflowNodeExecutor ex = executor(false, "fail");
        WorkflowNodeDto n = node("out1", "output", Map.of());

        run(ex, n, null);
        run(ex, n, null);

        assertEquals(2, sends.get(), "disabled = the legacy at-least-once behaviour");
        assertTrue(store.rows.isEmpty());
        assertEquals(java.util.Arrays.asList(null, null), seenKeys);
    }

    @Test
    void operatorCanResolveAnUncertainEffectEitherWay() {
        WorkflowNodeExecutor ex = executor(true, "fail");
        WorkflowEffectJournal journal = new WorkflowEffectJournal(store.proxy(), true, "fail", 200_000);
        UUID run = UUID.fromString(RUN);
        store.seedStarted(run, "out1", "output", "-");
        assertEquals(1, journal.markRunEffectsUncertain(run));
        assertEquals(1, journal.uncertainEffects(run).size());

        // "it did happen": recorded as done, replayed from now on
        assertTrue(journal.resolveUncertain(run, "out1", "-", true, "{\"id\":42}"));
        NodeExecutionResult r = run(ex, node("out1", "output", Map.of()), null);
        assertEquals("{\"id\":42}", r.output());
        assertEquals(0, sends.get());

        // "it did not happen": released, the node may run
        store.seedStarted(run, "out2", "output", "-");
        assertTrue(journal.resolveUncertain(run, "out2", "-", false, null));
        run(ex, node("out2", "output", Map.of()), null);
        assertEquals(1, sends.get());
        assertFalse(journal.resolveUncertain(run, "nope", "-", true, "x"));
    }
}
