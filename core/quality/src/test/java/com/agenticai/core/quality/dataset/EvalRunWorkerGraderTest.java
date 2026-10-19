package com.agenticai.core.quality.dataset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.quality.AggregatedEvaluationResult;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * The run worker's per-case pass decision: repetitions resolved by strict majority, and the V375
 * deterministic code graders (alone, alongside the judge, voted across repetitions, disabled, corrupt).
 */
class EvalRunWorkerGraderTest extends EvalRunServiceFixture {

    // ------------------------------------------------------------------
    // Worker: repeats -> majority pass
    // ------------------------------------------------------------------

    @Test
    void aCasePassesOnAStrictMajorityOfRepetitions() throws Exception {
        judgeScores(0.9, 0.1, 0.9); // 2 of 3 pass the 0.7 bar
        when(replayer.replay(eq(AGENT_ID), anyString(), anyString(), anyString(), eq(false), any(Duration.class)))
            .thenReturn(new AgentTurnReplayer.ReplayedTurn("answer", List.of()));
        ArgumentCaptor<Runnable> captor = ArgumentCaptor.forClass(Runnable.class);
        when(runExecutor.trySubmit(captor.capture())).thenReturn(true);

        EvalRun run = triggerAndCapture(3);
        captor.getValue().run();

        EvalRun stored = runStore.get(run.getId());
        assertEquals(EvalRunStatus.SUCCEEDED, stored.getStatus());
        EvalRunCaseResult result = resultStore.values().iterator().next();
        assertEquals(Boolean.TRUE, result.getPassed());
        // The representative repetition's mean score is recorded.
        assertNotNull(result.getOverallScore());
        verify(replayer, times(3)).replay(
            eq(AGENT_ID), anyString(), anyString(), anyString(), eq(false), any(Duration.class));
    }

    @Test
    void aCaseFailsWhenTheMajorityOfRepetitionsFail() throws Exception {
        judgeScores(0.9, 0.1, 0.1); // only 1 of 3 passes -> majority FAIL
        when(replayer.replay(eq(AGENT_ID), anyString(), anyString(), anyString(), eq(false), any(Duration.class)))
            .thenReturn(new AgentTurnReplayer.ReplayedTurn("answer", List.of()));
        ArgumentCaptor<Runnable> captor = ArgumentCaptor.forClass(Runnable.class);
        when(runExecutor.trySubmit(captor.capture())).thenReturn(true);

        EvalRun run = triggerAndCapture(3);
        captor.getValue().run();

        EvalRunCaseResult result = resultStore.values().iterator().next();
        assertEquals(Boolean.FALSE, result.getPassed());
        assertEquals(EvalRunVerdict.FAILED, runStore.get(run.getId()).getVerdict());
    }

    // ------------------------------------------------------------------
    // Worker: code graders (V375)
    // ------------------------------------------------------------------

    @Test
    void aFailingGraderFailsTheCaseEvenWhenTheJudgeScoresHigh() throws Exception {
        graderCase("{\"type\":\"exact\",\"value\":\"42\"}");
        judgeScores(0.95);
        replies("forty-three");

        EvalRun stored = runInline(1);

        EvalRunCaseResult result = resultStore.values().iterator().next();
        assertEquals(Boolean.FALSE, result.getPassed());
        assertEquals(1, stored.getFailedAssertionCount());
        assertTrue(result.getScoresJson().contains(EvalRunService.CODE_GRADER_DIMENSION));
    }

    @Test
    void graderRunScoresThePassFractionSoOneMissDoesNotFailTheRun() {
        // 9 of 10 graded cases passed: score 0.9 clears a 0.7 bar (a grader miss is no longer a hard failure).
        Double score = EvalRunService.runScore(true, 10, 9, 5.0);
        assertEquals(0.9, score, 1e-9);
        assertEquals(EvalRunVerdict.PASSED, EvalRunService.computeVerdict(score, 0.7, 10, 0, 0));
        // 5 of 10 does not.
        assertEquals(EvalRunVerdict.FAILED,
            EvalRunService.computeVerdict(EvalRunService.runScore(true, 10, 5, 9.0), 0.7, 10, 0, 0));
    }

    @Test
    void runWithoutGradersKeepsTheMeanScoreAndNullWhenNothingScored() {
        assertEquals(0.5, EvalRunService.runScore(false, 4, 1, 2.0), 1e-9);
        assertNull(EvalRunService.runScore(true, 0, 0, 0.0));
    }

    @Test
    void aPassingGraderWithJudgeAboveTheBarPasses() throws Exception {
        graderCase("{\"type\":\"exact\",\"value\":\"42\"}");
        judgeScores(0.95);
        replies("42");

        EvalRun stored = runInline(1);

        assertEquals(Boolean.TRUE, resultStore.values().iterator().next().getPassed());
        assertEquals(0, stored.getFailedAssertionCount());
    }

    @Test
    void withNoJudgeScoreTheGraderVerdictBecomesTheCaseScore() throws Exception {
        graderCase("{\"type\":\"exact\",\"value\":\"42\"}");
        when(evalEngine.evaluate(any(), any())).thenReturn(new AggregatedEvaluationResult(Map.of(), null));
        replies("42");

        runInline(1);

        EvalRunCaseResult result = resultStore.values().iterator().next();
        assertEquals(Double.valueOf(1.0), result.getOverallScore());
        assertEquals(Boolean.TRUE, result.getPassed());
    }

    @Test
    void aFailingGraderWithNoJudgeScoreScoresZeroAndFails() throws Exception {
        graderCase("{\"type\":\"exact\",\"value\":\"42\"}");
        when(evalEngine.evaluate(any(), any())).thenReturn(new AggregatedEvaluationResult(Map.of(), null));
        replies("nope");

        EvalRun stored = runInline(1);

        EvalRunCaseResult result = resultStore.values().iterator().next();
        assertEquals(Double.valueOf(0.0), result.getOverallScore());
        assertEquals(Boolean.FALSE, result.getPassed());
        assertEquals(1, stored.getFailedAssertionCount());
    }

    @Test
    void graderVerdictsAreMajorityVotedAcrossRepetitions() throws Exception {
        graderCase("{\"type\":\"exact\",\"value\":\"42\"}");
        judgeScores(0.9, 0.9, 0.9);
        replies("42", "42", "nope"); // 2 of 3 pass -> case passes, no failed assertion

        EvalRun stored = runInline(3);

        assertEquals(Boolean.TRUE, resultStore.values().iterator().next().getPassed());
        assertEquals(0, stored.getFailedAssertionCount());
    }

    @Test
    void aMajorityOfFailingGraderRepetitionsFailsTheCase() throws Exception {
        graderCase("{\"type\":\"exact\",\"value\":\"42\"}");
        judgeScores(0.9, 0.9, 0.9);
        replies("42", "nope", "nope");

        EvalRun stored = runInline(3);

        assertEquals(Boolean.FALSE, resultStore.values().iterator().next().getPassed());
        assertEquals(1, stored.getFailedAssertionCount());
    }

    @Test
    void gradersAreIgnoredWhenDisabled() throws Exception {
        service.codeGradersEnabled = false;
        graderCase("{\"type\":\"exact\",\"value\":\"42\"}");
        judgeScores(0.95);
        replies("nope");

        EvalRun stored = runInline(1);

        assertEquals(Boolean.TRUE, resultStore.values().iterator().next().getPassed());
        assertEquals(0, stored.getFailedAssertionCount());
    }

    @Test
    void aCorruptStoredGraderSpecDoesNotFailOrPassTheCase() throws Exception {
        graderCase("{not json");
        judgeScores(0.95);
        replies("anything");

        runInline(1);

        EvalRunCaseResult result = resultStore.values().iterator().next();
        assertEquals(Boolean.TRUE, result.getPassed()); // judge decides; no grader dimension
        assertTrue(!result.getScoresJson().contains(EvalRunService.CODE_GRADER_DIMENSION));
    }
}
