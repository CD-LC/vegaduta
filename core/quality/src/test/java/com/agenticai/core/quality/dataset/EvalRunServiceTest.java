package com.agenticai.core.quality.dataset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * {@link EvalRunService} trigger side: the V343 run options (strictJudge self-grading guard, repeats
 * bounds, budget bounds, idempotency), the best-effort V375 agent-config snapshot, cooperative cancel at
 * pickup and the verdict rules. The worker's case loop is covered by {@link EvalRunWorkerGraderTest} and
 * {@link EvalRunWorkerJudgeOutageTest}.
 */
class EvalRunServiceTest extends EvalRunServiceFixture {

    // ------------------------------------------------------------------
    // Trigger-time validation (V343)
    // ------------------------------------------------------------------

    @Test
    void repeatsAreBoundedToOneThroughFive() {
        when(runExecutor.trySubmit(any())).thenReturn(true);

        assertThrows(IllegalArgumentException.class, () ->
            service.triggerRun(AGENT_ID, DATASET_ID, options(null, null, 0, null, null)));
        assertThrows(IllegalArgumentException.class, () ->
            service.triggerRun(AGENT_ID, DATASET_ID, options(null, null, 6, null, null)));
    }

    @Test
    void defaultRepeatsAppliesWhenTriggerNamesNoneAndIsClamped() {
        when(runExecutor.trySubmit(any())).thenReturn(true);

        assertEquals(1, service.triggerRun(AGENT_ID, DATASET_ID,
            options(null, null, null, null, null)).getRepeats());

        service.defaultRepeats = 3;
        assertEquals(3, service.triggerRun(AGENT_ID, DATASET_ID,
            options(null, null, null, null, null)).getRepeats());

        service.defaultRepeats = 99;
        assertEquals(5, service.triggerRun(AGENT_ID, DATASET_ID,
            options(null, null, null, null, null)).getRepeats());

        service.defaultRepeats = 0;
        assertEquals(1, service.triggerRun(AGENT_ID, DATASET_ID,
            options(null, null, null, null, null)).getRepeats());

        // An explicit value always wins over the configured default.
        service.defaultRepeats = 3;
        assertEquals(2, service.triggerRun(AGENT_ID, DATASET_ID,
            options(null, null, 2, null, null)).getRepeats());
    }

    @Test
    void budgetMustBePositiveAndBounded() {
        assertThrows(IllegalArgumentException.class, () ->
            service.triggerRun(AGENT_ID, DATASET_ID, options(null, null, null, BigDecimal.ZERO, null)));
        assertThrows(IllegalArgumentException.class, () ->
            service.triggerRun(AGENT_ID, DATASET_ID, options(null, null, null, new BigDecimal("-1"), null)));
        assertThrows(IllegalArgumentException.class, () ->
            service.triggerRun(AGENT_ID, DATASET_ID, options(null, null, null, new BigDecimal("9999"), null)));
    }

    @Test
    void strictJudgeRejectsAJudgeIdenticalToTheAgentModel() {
        // Factory resolves "OPENAI/gpt-judge"; request an override whose model
        // part equals the agent's own model "gpt-agent".
        assertThrows(IllegalArgumentException.class, () ->
            service.triggerRun(AGENT_ID, DATASET_ID, options("gpt-agent", true, null, null, null)));

        // And the DEFAULT judge identical to the agent's model also rejects.
        when(judgeChatClientFactory.resolveJudgeModelIdOrNull()).thenReturn("OPENAI/gpt-agent");
        assertThrows(IllegalArgumentException.class, () ->
            service.triggerRun(AGENT_ID, DATASET_ID, options(null, true, null, null, null)));
    }

    @Test
    void identicalJudgeModelIsAllowedWithoutStrictJudge() {
        when(judgeChatClientFactory.resolveJudgeModelIdOrNull()).thenReturn("OPENAI/gpt-agent");
        when(runExecutor.trySubmit(any())).thenReturn(true);

        EvalRun run = service.triggerRun(AGENT_ID, DATASET_ID,
            options(null, false, null, null, null));

        assertNotNull(run.getId());
        assertEquals(EvalRunStatus.QUEUED, run.getStatus());
    }

    @Test
    void triggerSnapshotsTheDatasetHashAndOptionsOntoTheRun() {
        when(runExecutor.trySubmit(any())).thenReturn(true);

        EvalRun run = service.triggerRun(AGENT_ID, DATASET_ID,
            options("claude-judge", true, 3, new BigDecimal("2.50"), "ci-key-1"));

        assertEquals("hash-abc", run.getDatasetHash());
        assertEquals("claude-judge", run.getRequestedJudgeModel());
        assertTrue(run.isStrictJudge());
        assertEquals(3, run.getRepeats());
        assertEquals(new BigDecimal("2.50"), run.getBudgetUsd());
        assertEquals("ci-key-1", run.getIdempotencyKey());
    }

    @Test
    void anIdempotencyKeyReplaysTheExistingRun() {
        EvalRun prior = new EvalRun(DATASET_ID, AGENT_ID, "tenant-1", "tester", "", 0.7, 1);
        ReflectionTestUtils.setField(prior, "id", UUID.randomUUID());
        when(runRepository.findByDatasetIdAndIdempotencyKey(DATASET_ID, "ci-key-1"))
            .thenReturn(Optional.of(prior));

        EvalRun run = service.triggerRun(AGENT_ID, DATASET_ID,
            options(null, null, null, null, "ci-key-1"));

        assertEquals(prior.getId(), run.getId());
        verify(runExecutor, never()).trySubmit(any());
    }

    @Test
    void triggerRejectsAnEmptyDataset() {
        when(caseRepository.countByDatasetId(DATASET_ID)).thenReturn(0L);

        assertThrows(IllegalArgumentException.class, () ->
            service.triggerRun(AGENT_ID, DATASET_ID));
    }

    // ------------------------------------------------------------------
    // Trigger: agent-config snapshot (V375) is best-effort
    // ------------------------------------------------------------------

    @Test
    void snapshotIsRecordedOnTheRunWhenAvailable() {
        when(runExecutor.trySubmit(any())).thenReturn(true);
        when(agentDefinitionLookup.findSnapshot(AGENT_ID)).thenReturn(Optional.of(
            new AgentConfigSnapshot("gpt-agent", 0.2, null, null, 7, "abc", List.of("web_search"))));

        EvalRun run = triggerAndCapture(1);

        assertEquals("gpt-agent", run.getAgentModel());
        assertEquals(Integer.valueOf(7), run.getAgentSeed());
        assertNotNull(run.getAgentConfigHash());
    }

    @Test
    void aNullOrThrowingSnapshotLookupNeverBlocksTheTrigger() {
        when(runExecutor.trySubmit(any())).thenReturn(true);

        when(agentDefinitionLookup.findSnapshot(AGENT_ID)).thenReturn(null);
        assertEquals(null, triggerAndCapture(1).getAgentConfigHash());

        when(agentDefinitionLookup.findSnapshot(AGENT_ID)).thenThrow(new RuntimeException("sql boom"));
        assertEquals(null, triggerAndCapture(1).getAgentConfigHash());
    }

    @Test
    void snapshotIsSkippedWhenDisabled() {
        when(runExecutor.trySubmit(any())).thenReturn(true);
        service.snapshotEnabled = false;

        triggerAndCapture(1);

        verify(agentDefinitionLookup, never()).findSnapshot(any());
    }

    // ------------------------------------------------------------------
    // Worker: cooperative cancel
    // ------------------------------------------------------------------

    @Test
    void aCancelRequestedRunCancelsAtPickupWithoutReplaying() throws Exception {
        when(runExecutor.trySubmit(any())).thenReturn(true);
        EvalRun run = service.triggerRun(AGENT_ID, DATASET_ID);
        run.requestCancel("tester");
        runRepository.save(run);

        ArgumentCaptor<Runnable> captor = ArgumentCaptor.forClass(Runnable.class);
        verify(runExecutor).trySubmit(captor.capture());
        captor.getValue().run();

        assertEquals(EvalRunStatus.CANCELLED, runStore.get(run.getId()).getStatus());
        verify(replayer, never()).replay(any(), any(), any(), any(), any(Boolean.class), any());
    }

    @Test
    void computeVerdictRules() {
        assertEquals(EvalRunVerdict.FAILED, EvalRunService.computeVerdict(0.9, 0.7, 1, 0, 1));
        assertEquals(EvalRunVerdict.FAILED, EvalRunService.computeVerdict(0.9, 0.7, 1, 1, 0));
        assertEquals(EvalRunVerdict.INCONCLUSIVE, EvalRunService.computeVerdict(null, 0.7, 0, 0, 0));
        assertEquals(EvalRunVerdict.PASSED, EvalRunService.computeVerdict(0.8, 0.7, 2, 0, 0));
        assertEquals(EvalRunVerdict.FAILED, EvalRunService.computeVerdict(0.5, 0.7, 2, 0, 0));
        // Epsilon boundary: 0.6999999999 counts as at the 0.7 bar.
        assertEquals(EvalRunVerdict.PASSED, EvalRunService.computeVerdict(0.7 - 1e-10, 0.7, 1, 0, 0));
    }
}
