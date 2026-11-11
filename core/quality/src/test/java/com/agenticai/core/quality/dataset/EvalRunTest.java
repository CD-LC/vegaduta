package com.agenticai.core.quality.dataset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The run's state machine lives in the entity so no caller can forget a guard; these tests pin the
 * legal transitions, the terminal-state refusals, and the trigger-time snapshots (mode, judge
 * config, agent config hash) a finished run must keep verbatim. {@link EvalMode}'s parsing rules
 * and {@link CanonicalJson}'s canonical form are exercised alongside, since the entity is their
 * main consumer.
 */
class EvalRunTest {

    private static final UUID DATASET = UUID.randomUUID();
    private static final UUID AGENT = UUID.randomUUID();

    private static EvalRun queuedRun() {
        return new EvalRun(
                DATASET, AGENT, "tenant-1", "tester@example.com", "tenant-admin", 0.7, 5);
    }

    // ---------------------------------------------------------------- lifecycle

    @Test
    void aNewRunIsQueuedWithSaneDefaults() {
        EvalRun run = queuedRun();

        assertEquals(EvalRunStatus.QUEUED, run.getStatus());
        assertNull(run.getVerdict());
        assertEquals(DATASET, run.getDatasetId());
        assertEquals(AGENT, run.getAgentId());
        assertEquals("tenant-1", run.getTenantId());
        assertEquals("tester@example.com", run.getRequestedBy());
        assertEquals("tenant-admin", run.getRequestedByRoles());
        assertEquals(0.7, run.getPassThreshold());
        assertEquals(5, run.getCaseCount());
        assertEquals(1, run.getRepeats());
        assertEquals(0, BigDecimal.valueOf(5.00).compareTo(run.getBudgetUsd()));
        assertFalse(run.isCancelRequested());
        assertFalse(run.isAutoApproved());
        assertEquals(
                EvalMode.QUICK,
                run.getEvalMode(),
                "a run with no bound mode falls back to QUICK, the cheap default");
        assertNotNull(run.getCreatedAt());
        assertNull(run.getStartedAt());
        assertNull(run.getFinishedAt());
    }

    @Test
    void queuedToRunningToSucceededIsTheHappyPath() {
        EvalRun run = queuedRun();

        run.markRunning("judge-model-1");
        assertEquals(EvalRunStatus.RUNNING, run.getStatus());
        assertEquals("judge-model-1", run.getJudgeModel());
        assertNotNull(run.getStartedAt());

        run.complete(0.85, 4, 0, 1, EvalRunVerdict.FAILED);
        assertEquals(EvalRunStatus.SUCCEEDED, run.getStatus());
        assertEquals(0.85, run.getOverallScore());
        assertEquals(4, run.getScoredCaseCount());
        assertEquals(0, run.getFailedAssertionCount());
        assertEquals(1, run.getErroredCaseCount());
        assertEquals(EvalRunVerdict.FAILED, run.getVerdict());
        assertNotNull(run.getFinishedAt());
    }

    @Test
    void illegalTransitionsAreRefusedFromTheEntityNotTheCaller() {
        EvalRun run = queuedRun();

        assertThrows(
                IllegalStateException.class,
                () -> run.complete(0.5, 1, 0, 0, EvalRunVerdict.PASSED),
                "complete() requires RUNNING - a queued run cannot skip pickup");

        run.markRunning(null);
        assertThrows(
                IllegalStateException.class,
                () -> run.markRunning("j"),
                "a running run cannot be picked up twice");

        run.complete(0.9, 5, 0, 0, EvalRunVerdict.PASSED);
        assertThrows(
                IllegalStateException.class,
                () -> run.fail("too late"),
                "a terminal run cannot fail");
        assertThrows(
                IllegalStateException.class,
                run::markCancelled,
                "a terminal run cannot be cancelled");
    }

    @Test
    void failIsReachableFromAnyNonTerminalState() {
        EvalRun fromQueued = queuedRun();
        fromQueued.fail("executor rejected");
        assertEquals(EvalRunStatus.FAILED, fromQueued.getStatus());
        assertEquals("executor rejected", fromQueued.getErrorMessage());
        assertNotNull(fromQueued.getFinishedAt());

        EvalRun fromRunning = queuedRun();
        fromRunning.markRunning("j");
        fromRunning.fail("agent deleted mid-run");
        assertEquals(EvalRunStatus.FAILED, fromRunning.getStatus());
    }

    @Test
    void cancellationIsCooperativeAndExplained() {
        EvalRun run = queuedRun();
        run.requestCancel("ops@example.com");
        assertTrue(run.isCancelRequested());
        assertEquals("ops@example.com", run.getCancelRequestedBy());
        assertEquals(
                EvalRunStatus.QUEUED,
                run.getStatus(),
                "requestCancel never writes status - only the owning worker does");

        run.markRunning("j");
        run.cancelWithNote("budget cap exceeded");
        assertEquals(EvalRunStatus.CANCELLED, run.getStatus());
        assertEquals("budget cap exceeded", run.getErrorMessage());
    }

    // ---------------------------------------------------------------- trigger-time configuration

    @Test
    void runConfigIsSnapshottedBeforeFirstSave() {
        EvalRun run = queuedRun();

        run.applyRunConfig(
                "abc123hash", "requested-judge", true, 3, BigDecimal.valueOf(2.50), "idem-key-1");

        assertEquals("abc123hash", run.getDatasetHash());
        assertEquals("requested-judge", run.getRequestedJudgeModel());
        assertTrue(run.isStrictJudge());
        assertEquals(3, run.getRepeats());
        assertEquals(0, BigDecimal.valueOf(2.50).compareTo(run.getBudgetUsd()));
        assertEquals("idem-key-1", run.getIdempotencyKey());
    }

    @Test
    void modeAutoApprovalCostAndVersionBindingAreRecorded() {
        EvalRun run = queuedRun();

        run.bindMode(EvalMode.FULL);
        run.markAutoApproved();
        run.recordCost(BigDecimal.valueOf(1.25));
        UUID version = UUID.randomUUID();
        run.bindAgentVersion(version);

        assertEquals(EvalMode.FULL, run.getEvalMode());
        assertTrue(run.isAutoApproved());
        assertEquals(0, BigDecimal.valueOf(1.25).compareTo(run.getCostUsd()));
        assertEquals(version, run.getAgentVersionId());
    }

    @Test
    void theAgentConfigSnapshotIsStoredAsCanonicalJsonPlusItsHash() {
        EvalRun run = queuedRun();
        AgentConfigSnapshot snapshot =
                new AgentConfigSnapshot(
                        "model-x", 0.7, null, 512, 42, "prompt-sha", List.of("tool-a", "tool-b"));

        run.applyAgentSnapshot(snapshot);

        assertEquals("model-x", run.getAgentModel());
        assertEquals(0.7, run.getAgentTemperature());
        assertEquals(42, run.getAgentSeed());
        assertEquals(snapshot.canonicalJson(), run.getAgentConfigJson());
        assertEquals(snapshot.hash(), run.getAgentConfigHash());
        assertEquals(64, run.getAgentConfigHash().length());
    }

    // ---------------------------------------------------------------- EvalMode

    @Test
    void modeParsingIsCaseInsensitiveStrictAndExplicitAboutJunk() {
        assertEquals(Optional.of(EvalMode.FULL), EvalMode.parse("full"));
        assertEquals(Optional.of(EvalMode.QUICK), EvalMode.parse(" Quick "));
        assertEquals(Optional.empty(), EvalMode.parse(null));
        assertEquals(Optional.empty(), EvalMode.parse("   "));

        IllegalArgumentException thrown =
                assertThrows(IllegalArgumentException.class, () -> EvalMode.parse("turbo"));
        assertTrue(thrown.getMessage().contains("FULL or QUICK"));
    }

    @Test
    void lenientModeNeverThrowsForStoredRows() {
        assertEquals(EvalMode.FULL, EvalMode.lenient("turbo", EvalMode.FULL));
        assertEquals(EvalMode.QUICK, EvalMode.lenient(null, EvalMode.QUICK));
        assertEquals(EvalMode.FULL, EvalMode.lenient("full", EvalMode.QUICK));
        assertTrue(EvalMode.FULL.executesTools());
        assertFalse(EvalMode.QUICK.executesTools());
    }

    // ---------------------------------------------------------------- CanonicalJson / snapshot

    @Test
    void canonicalJsonSortsKeysAtEveryLevel() {
        java.util.Map<String, Object> doc = new java.util.LinkedHashMap<>();
        doc.put("z", 1);
        doc.put("a", java.util.Map.of("y", 2, "b", 3));

        assertEquals("{\"a\":{\"b\":3,\"y\":2},\"z\":1}", CanonicalJson.write(doc));
    }

    @Test
    void sha256HexIsLowerHexDeterministicAndInputSensitive() {
        String a = CanonicalJson.sha256Hex("the same input");
        String b = CanonicalJson.sha256Hex("the same input");
        String c = CanonicalJson.sha256Hex("the same input.");

        assertEquals(64, a.length());
        assertEquals(a, b);
        assertTrue(a.matches("[0-9a-f]+"));
        assertFalse(a.equals(c));
    }

    @Test
    void theSnapshotHashCoversTheCanonicalDocumentOnly() {
        AgentConfigSnapshot snapshot =
                new AgentConfigSnapshot("m", null, null, null, null, null, null);

        assertEquals(snapshot.hash(), CanonicalJson.sha256Hex(snapshot.canonicalJson()));
        assertTrue(
                snapshot.canonicalJson().contains("\"tools\":[]"),
                "a null allow-list serialises as empty - 'no restriction', not 'no tools'");
    }
}
