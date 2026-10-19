package com.agenticai.core.quality.dataset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.chat.LlmCallLog;
import com.agenticai.core.quality.AggregatedEvaluationResult;
import com.agenticai.core.quality.EvaluationResult;
import com.agenticai.core.quality.EvaluationType;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * The run worker's stop conditions: the V343 budget cap, and a judge that cannot answer (preflight
 * failure, every judge call failing mid-run, the three-case outage abort) - prod signed-in pass
 * 2026-10-05, bugs 1 + 7.
 */
class EvalRunWorkerJudgeOutageTest extends EvalRunServiceFixture {

    // ------------------------------------------------------------------
    // Worker: budget cap
    // ------------------------------------------------------------------

    @Test
    void exceedingTheBudgetCancelsTheRunBeforeRemainingCasesReplay() throws Exception {
        // Two cases; each costs $3 -> after case 2 the $5 budget is exceeded.
        when(caseRepository.countByDatasetId(DATASET_ID)).thenReturn(2L);
        when(caseRepository.findByDatasetIdOrderByPositionAscCreatedAtAsc(DATASET_ID))
            .thenReturn(List.of(
                new EvalDatasetCase(DATASET_ID, "c0", null, null, 0),
                new EvalDatasetCase(DATASET_ID, "c1", null, null, 1),
                new EvalDatasetCase(DATASET_ID, "c2", null, null, 2)));
        when(caseRepository.countByDatasetId(DATASET_ID)).thenReturn(3L);
        judgeScores(0.9, 0.9, 0.9);
        when(replayer.replay(eq(AGENT_ID), anyString(), anyString(), anyString(), eq(false), any(Duration.class)))
            .thenReturn(new AgentTurnReplayer.ReplayedTurn("answer", List.of()));
        LlmCallLog call = mock(LlmCallLog.class);
        when(call.getEstimatedCostUsd()).thenReturn(new BigDecimal("3.00"));
        when(llmCallLogService.forTask(anyString())).thenReturn(List.of(call));
        ArgumentCaptor<Runnable> captor = ArgumentCaptor.forClass(Runnable.class);
        when(runExecutor.trySubmit(captor.capture())).thenReturn(true);

        EvalRun run = service.triggerRun(AGENT_ID, DATASET_ID,
            options(null, null, null, new BigDecimal("5.00"), null));
        captor.getValue().run();

        EvalRun stored = runStore.get(run.getId());
        assertEquals(EvalRunStatus.CANCELLED, stored.getStatus());
        assertNotNull(stored.getErrorMessage());
        assertTrue(stored.getErrorMessage().contains("budget"));
        // Two cases replayed ($6 observed), the third never did.
        verify(replayer, times(2)).replay(
            eq(AGENT_ID), anyString(), anyString(), anyString(), eq(false), any(Duration.class));
    }

    // ------------------------------------------------------------------
    // Worker: judge unavailable (prod signed-in pass 2026-10-05, bugs 1 + 7)
    // ------------------------------------------------------------------

    @Test
    void anUnusableJudgeFailsTheRunAtPreflightBeforeAnyCaseIsReplayed() throws Exception {
        when(judgeChatClientFactory.resolveJudgeModelIdOrNull()).thenReturn("GOOGLE_VERTEX/gemini-2.0-flash-001");
        when(judgeChatClientFactory.probeUnavailableReason()).thenReturn(Optional.of(REJECTED));

        EvalRun stored = runInline(1);

        assertEquals(EvalRunStatus.FAILED, stored.getStatus());
        assertNull(stored.getOverallScore());
        assertNull(stored.getVerdict());
        assertTrue(stored.getErrorMessage().startsWith(
            "Judge model unavailable (GOOGLE_VERTEX/gemini-2.0-flash-001): " + REJECTED), stored.getErrorMessage());
        verify(replayer, never()).replay(any(), anyString(), anyString(), anyString(), eq(false), any(Duration.class));
        verify(evalEngine, never()).evaluate(any(), any());
    }

    /**
     * The exact prod shape: every judge call failed, the Length heuristic
     * alone said 0.25, and the run was shown as "Completed - score 25%".
     */
    @Test
    void aRunWhoseJudgeCallsAllFailedIsFailedWithTheReasonNotScoredFromTheLengthHeuristic() throws Exception {
        service.judgePreflightEnabled = false; // the judge dies after a passing preflight
        when(evalEngine.evaluate(any(), any())).thenReturn(judgeDownLengthOnly());
        replies("hi");

        EvalRun stored = runInline(3);

        assertEquals(EvalRunStatus.FAILED, stored.getStatus());
        assertNull(stored.getOverallScore());
        assertNull(stored.getVerdict());
        assertTrue(stored.getErrorMessage().contains("Judge model OPENAI/gpt-judge returned no usable verdicts "
            + "(1 of 1 cases failed: " + REJECTED.substring(0, REJECTED.length() - 1) + ")"),
            stored.getErrorMessage());
        // The case row keeps the per-dimension reasons but carries no score.
        EvalRunCaseResult result = resultStore.values().iterator().next();
        assertNull(result.getOverallScore());
    }

    @Test
    void aJudgeOutageStopsTheRunAfterThreeCasesInsteadOfReplayingTheRest() throws Exception {
        service.judgePreflightEnabled = false;
        List<EvalDatasetCase> cases = new java.util.ArrayList<>();
        for (int i = 0; i < 10; i++) {
            cases.add(new EvalDatasetCase(DATASET_ID, "c" + i, null, null, i));
        }
        when(caseRepository.countByDatasetId(DATASET_ID)).thenReturn(10L);
        when(caseRepository.findByDatasetIdOrderByPositionAscCreatedAtAsc(DATASET_ID)).thenReturn(cases);
        when(evalEngine.evaluate(any(), any())).thenReturn(judgeDownLengthOnly());
        replies("hi");

        EvalRun stored = runInline(1);

        assertEquals(EvalRunStatus.FAILED, stored.getStatus());
        assertTrue(stored.getErrorMessage().contains("Stopped after 3 of 10 cases"), stored.getErrorMessage());
        verify(replayer, times(EvalRunService.JUDGE_OUTAGE_ABORT_AFTER)).replay(
            eq(AGENT_ID), anyString(), anyString(), anyString(), eq(false), any(Duration.class));
    }

    @Test
    void aDeterministicGraderStillScoresTheCaseWhenTheJudgeIsDown() throws Exception {
        service.judgePreflightEnabled = false;
        graderCase("{\"type\":\"exact\",\"value\":\"42\"}");
        when(evalEngine.evaluate(any(), any())).thenReturn(judgeDownLengthOnly());
        replies("42");

        EvalRun stored = runInline(1);

        assertEquals(EvalRunStatus.SUCCEEDED, stored.getStatus());
        assertEquals(Double.valueOf(1.0), resultStore.values().iterator().next().getOverallScore());
    }

    @Test
    void aTurnWhereSomeJudgeCallsWorkedIsAnOrdinaryPartialMeasurement() {
        Map<EvaluationType, EvaluationResult> results = new HashMap<>();
        results.put(EvaluationType.ACCURACY, EvaluationResult.of(EvaluationType.ACCURACY, 0.8, "fb"));
        results.put(EvaluationType.TONE, EvaluationResult.judgeUnavailable(EvaluationType.TONE, "timeout"));
        results.put(EvaluationType.FAITHFULNESS,
            EvaluationResult.unavailable(EvaluationType.FAITHFULNESS, "Not applicable - no retrieved context"));

        assertNull(EvalRunService.judgeOutageReason(new AggregatedEvaluationResult(results, 0.8)));
        // Not-applicable alone (no judge failure) is not an outage either.
        assertNull(EvalRunService.judgeOutageReason(new AggregatedEvaluationResult(
            Map.of(EvaluationType.FAITHFULNESS,
                EvaluationResult.unavailable(EvaluationType.FAITHFULNESS, "Not applicable")), null)));
        assertEquals(REJECTED, EvalRunService.judgeOutageReason(judgeDownLengthOnly()));
    }
}
