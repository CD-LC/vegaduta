package com.agenticai.core.quality.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.quality.dataset.EvalCaseResultStatus;
import com.agenticai.core.quality.dataset.EvalDataset;
import com.agenticai.core.quality.dataset.EvalDatasetNotFoundException;
import com.agenticai.core.quality.dataset.EvalDatasetRepository;
import com.agenticai.core.quality.dataset.EvalRun;
import com.agenticai.core.quality.dataset.EvalRunCaseResult;
import com.agenticai.core.quality.dataset.EvalRunCaseResultRepository;
import com.agenticai.core.quality.dataset.EvalRunNotFoundException;
import com.agenticai.core.quality.dataset.EvalRunStatus;
import com.agenticai.core.quality.stats.dto.ReliabilityDtos.AgentReliabilityResponse;
import com.agenticai.core.quality.stats.dto.ReliabilityDtos.CaseReliabilityResponse;
import com.agenticai.core.quality.stats.dto.ReliabilityDtos.DatasetNoiseFloorResponse;
import com.agenticai.core.quality.stats.dto.ReliabilityDtos.DatasetReliabilityResponse;
import com.agenticai.core.quality.stats.dto.ReliabilityDtos.MetricResponse;
import com.agenticai.core.quality.stats.dto.ReliabilityDtos.PairedComparisonResponse;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

/**
 * Tests the RECONSTRUCTION rules - the part of this package that is judgement
 * rather than arithmetic.
 *
 * <p>The three rules being pinned are the ones that decide whether a tenant's
 * number is honest, and each has a way of going quietly wrong:
 * <ul>
 *   <li>An {@code UNAVAILABLE} case result (no judge provider, judge outage) must
 *       be EXCLUDED, not counted as a failure. Getting this wrong turns a
 *       provider incident into an agent regression - the single most damaging
 *       mistake this surface could make.</li>
 *   <li>An {@code ERROR} case result must count as a FAILED rollout but must NOT
 *       contribute a score. Getting this wrong either forgives crashes or invents
 *       a zero score that drags the continuous metric.</li>
 *   <li>A violated expected-tools assertion must fail the rollout even when the
 *       judge score cleared the bar - the rule {@code EvalRunService} already
 *       applies at the run level.</li>
 * </ul>
 *
 * <p>Entities are Mockito mocks rather than constructed instances because
 * {@code EvalRun}'s and {@code EvalRunCaseResult}'s ids are database-generated
 * and there is no setter for them; stubbing the getters is cleaner than
 * reflecting into JPA fields, and these tests are about the service's rules, not
 * about JPA.
 */
class EvalReliabilityServiceTest {

    private static final UUID AGENT_ID = UUID.randomUUID();
    private static final UUID DATASET_ID = UUID.randomUUID();
    private static final UUID OLDER_RUN_ID = UUID.randomUUID();
    private static final UUID NEWER_RUN_ID = UUID.randomUUID();
    private static final UUID CASE_1 = UUID.randomUUID();
    private static final UUID CASE_2 = UUID.randomUUID();
    private static final UUID CASE_3 = UUID.randomUUID();

    private EvalStatsRunRepository runRepository;
    private EvalRunCaseResultRepository caseResultRepository;
    private EvalDatasetRepository datasetRepository;
    private EvalReliabilityService service;

    @BeforeEach
    void setUp() {
        runRepository = mock(EvalStatsRunRepository.class);
        caseResultRepository = mock(EvalRunCaseResultRepository.class);
        datasetRepository = mock(EvalDatasetRepository.class);
        service = new EvalReliabilityService(runRepository, caseResultRepository, datasetRepository);
    }

    // ------------------------------------------------------------------
    // Fixture helpers
    // ------------------------------------------------------------------

    private static EvalDataset dataset() {
        EvalDataset dataset = mock(EvalDataset.class);
        when(dataset.getId()).thenReturn(DATASET_ID);
        when(dataset.getAgentId()).thenReturn(AGENT_ID);
        when(dataset.getName()).thenReturn("Golden set");
        return dataset;
    }

    private static EvalRun run(UUID runId, double passThreshold, String judgeModel) {
        EvalRun run = mock(EvalRun.class);
        when(run.getId()).thenReturn(runId);
        when(run.getAgentId()).thenReturn(AGENT_ID);
        when(run.getDatasetId()).thenReturn(DATASET_ID);
        when(run.getStatus()).thenReturn(EvalRunStatus.SUCCEEDED);
        when(run.getPassThreshold()).thenReturn(passThreshold);
        when(run.getJudgeModel()).thenReturn(judgeModel);
        when(run.getFinishedAt()).thenReturn(Instant.parse("2026-09-10T00:00:00Z"));
        return run;
    }

    private static EvalRunCaseResult scored(UUID caseId, int position, double score) {
        return result(caseId, position, EvalCaseResultStatus.SCORED, score, null);
    }

    private static EvalRunCaseResult unavailable(UUID caseId, int position) {
        return result(caseId, position, EvalCaseResultStatus.UNAVAILABLE, null, null);
    }

    private static EvalRunCaseResult errored(UUID caseId, int position) {
        return result(caseId, position, EvalCaseResultStatus.ERROR, null, null);
    }

    private static EvalRunCaseResult result(
        UUID caseId, int position, EvalCaseResultStatus status, Double score, Boolean toolsSatisfied
    ) {
        EvalRunCaseResult result = mock(EvalRunCaseResult.class);
        when(result.getId()).thenReturn(UUID.randomUUID());
        when(result.getCaseId()).thenReturn(caseId);
        when(result.getPosition()).thenReturn(position);
        when(result.getStatus()).thenReturn(status);
        when(result.getOverallScore()).thenReturn(score);
        when(result.getUserMessage()).thenReturn("case at position " + position);
        when(result.getExpectedToolsSatisfied()).thenReturn(toolsSatisfied);
        return result;
    }

    private void withDatasetAndRuns(List<EvalRun> newestFirst) {
        // The dataset mock is built BEFORE the when(...) below: creating and
        // stubbing a mock inside another stubbing's argument list leaves
        // Mockito's when(...) unfinished and throws UnfinishedStubbingException.
        EvalDataset dataset = dataset();
        when(datasetRepository.findByAgentIdOrderByCreatedAtDesc(AGENT_ID))
            .thenReturn(List.of(dataset));
        when(runRepository.findByDatasetIdAndStatusOrderByCreatedAtDesc(
            eq(DATASET_ID), eq(EvalRunStatus.SUCCEEDED), any(Pageable.class)))
            .thenReturn(newestFirst);
    }

    /**
     * Stubs one run's stored case results.
     *
     * <p>Varargs rather than a {@code List} argument for a Mockito reason worth
     * recording: the result mocks are created and stubbed while evaluating THIS
     * call's arguments, which happens before any {@code when(...)} inside the
     * method body begins. Inlining {@code when(repo.find(id)).thenReturn(List.of(scored(...)))}
     * instead stubs one mock in the middle of another mock's unfinished
     * stubbing, and Mockito rejects that with {@code UnfinishedStubbingException}.
     */
    private void withResults(UUID runId, EvalRunCaseResult... results) {
        List<EvalRunCaseResult> stored = List.of(results);
        when(caseResultRepository.findByRunIdOrderByPositionAscCreatedAtAsc(runId)).thenReturn(stored);
    }

    private static MetricResponse metric(DatasetReliabilityResponse dataset, ReliabilityMetricId id) {
        return dataset.metrics().stream()
            .filter(metric -> metric.metric() == id)
            .findFirst()
            .orElseThrow(() -> new AssertionError("metric " + id + " missing from the report"));
    }

    private static CaseReliabilityResponse caseRow(DatasetReliabilityResponse dataset, UUID caseId) {
        return dataset.cases().stream()
            .filter(row -> row.caseKey().equals(caseId.toString()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("case " + caseId + " missing from the report"));
    }

    // ------------------------------------------------------------------
    // Reconstruction from two runs
    // ------------------------------------------------------------------

    /**
     * Two runs of a three-case dataset at a 0.70 bar:
     * <pre>
     *   older : c1 SCORED 0.9 (pass)  c2 SCORED 0.5 (fail)  c3 UNAVAILABLE
     *   newer : c1 SCORED 0.8 (pass)  c2 SCORED 0.6 (fail)  c3 ERROR
     * </pre>
     * so c1 has two passing rollouts, c2 two failing ones, and c3 exactly one
     * rollout (the ERROR; the UNAVAILABLE contributes nothing at all).
     */
    @Test
    void reconstructsRolloutsFromRepeatedRunsAndAppliesTheStatusRules() {
        EvalRun older = run(OLDER_RUN_ID, 0.70, "claude-sonnet-4");
        EvalRun newer = run(NEWER_RUN_ID, 0.70, "claude-sonnet-4");
        withDatasetAndRuns(List.of(newer, older));
        withResults(OLDER_RUN_ID, scored(CASE_1, 0, 0.9), scored(CASE_2, 1, 0.5), unavailable(CASE_3, 2));
        withResults(NEWER_RUN_ID, scored(CASE_1, 0, 0.8), scored(CASE_2, 1, 0.6), errored(CASE_3, 2));

        AgentReliabilityResponse report = service.reportForAgent(AGENT_ID, 10);

        assertEquals(1, report.datasets().size());
        DatasetReliabilityResponse dataset = report.datasets().get(0);
        assertEquals(DATASET_ID, dataset.datasetId());
        assertEquals(List.of(NEWER_RUN_ID, OLDER_RUN_ID), dataset.runIds());
        assertEquals(3, dataset.caseCount());

        // 5 usable rollouts: c1 x2, c2 x2, c3 x1. Two of them passed.
        MetricResponse passAtOne = metric(dataset, ReliabilityMetricId.PASS_AT_1);
        assertEquals(0.4, passAtOne.value(), 1e-12);
        assertEquals(5, passAtOne.n());
        assertEquals(SampleUnit.ROLLOUT, passAtOne.unit());
        assertEquals(IntervalMethod.WILSON_SCORE, passAtOne.interval().method());

        // c3 was only observed once, so the whole suite reports at k=1 and says so.
        assertEquals(1, dataset.k());
        assertEquals(1, dataset.minRolloutsPerCase());
        assertEquals(2, dataset.maxRolloutsPerCase());
        assertTrue(dataset.unreplicated());
        assertTrue(
            dataset.warnings().stream().anyMatch(w -> w.contains("ragged")),
            "ragged replication must be called out, not hidden: " + dataset.warnings());
    }

    @Test
    void unavailableResultsAreExcludedAndErroredOnesCountAsFailures() {
        EvalRun older = run(OLDER_RUN_ID, 0.70, "judge-a");
        EvalRun newer = run(NEWER_RUN_ID, 0.70, "judge-a");
        withDatasetAndRuns(List.of(newer, older));
        withResults(OLDER_RUN_ID, scored(CASE_1, 0, 0.9), scored(CASE_2, 1, 0.5), unavailable(CASE_3, 2));
        withResults(NEWER_RUN_ID, scored(CASE_1, 0, 0.8), scored(CASE_2, 1, 0.6), errored(CASE_3, 2));

        DatasetReliabilityResponse dataset = service.reportForAgent(AGENT_ID, 10).datasets().get(0);

        CaseReliabilityResponse crashed = caseRow(dataset, CASE_3);
        assertEquals(1, crashed.rollouts(), "the UNAVAILABLE rollout must not appear at all");
        assertEquals(0, crashed.successes(), "the ERRORed rollout is a failure");
        assertEquals(0, crashed.scoredRollouts());
        assertNull(crashed.meanScore(),
            "an ERRORed replay produced no score, and imputing zero would corrupt the mean");

        CaseReliabilityResponse stable = caseRow(dataset, CASE_1);
        assertEquals(2, stable.rollouts());
        assertEquals(2, stable.successes());
        assertTrue(stable.allPassed());
        assertFalse(stable.unreplicated());
        assertEquals(0.85, stable.meanScore(), 1e-12);

        // Mean score averages the two SCORED cases' own means: (0.85 + 0.55)/2.
        MetricResponse meanScore = metric(dataset, ReliabilityMetricId.MEAN_SCORE);
        assertEquals(0.70, meanScore.value(), 1e-12);
        assertEquals(2, meanScore.n(), "c3 contributed no score, so it is not in this n");
        assertEquals(SampleUnit.CASE, meanScore.unit());
        assertEquals(IntervalMethod.HIERARCHICAL_BOOTSTRAP, meanScore.interval().method());
    }

    @Test
    void aViolatedExpectedToolsAssertionFailsTheRolloutDespiteAHighScore() {
        EvalRun only = run(NEWER_RUN_ID, 0.70, "judge-a");
        withDatasetAndRuns(List.of(only));
        withResults(NEWER_RUN_ID, result(CASE_1, 0, EvalCaseResultStatus.SCORED, 0.95, Boolean.FALSE), result(CASE_2, 1, EvalCaseResultStatus.SCORED, 0.95, Boolean.TRUE));

        DatasetReliabilityResponse dataset = service.reportForAgent(AGENT_ID, 10).datasets().get(0);

        assertEquals(0, caseRow(dataset, CASE_1).successes(),
            "a 0.95 score with the required tools unused is still a failed case");
        assertEquals(1, caseRow(dataset, CASE_2).successes());
        // The score itself is still recorded - the violation changes the verdict,
        // not the measurement.
        assertEquals(0.95, caseRow(dataset, CASE_1).meanScore(), 1e-12);
    }

    // ------------------------------------------------------------------
    // Noise floor and provenance
    // ------------------------------------------------------------------

    @Test
    void theNoiseFloorComesFromTheTwoMostRecentRuns() {
        EvalRun older = run(OLDER_RUN_ID, 0.70, "judge-a");
        EvalRun newer = run(NEWER_RUN_ID, 0.70, "judge-a");
        withDatasetAndRuns(List.of(newer, older));
        // c1 drifted down 0.1, c2 drifted up 0.1: mean |delta| = 0.1, signed 0.
        withResults(OLDER_RUN_ID, scored(CASE_1, 0, 0.9), scored(CASE_2, 1, 0.5));
        withResults(NEWER_RUN_ID, scored(CASE_1, 0, 0.8), scored(CASE_2, 1, 0.6));

        DatasetReliabilityResponse dataset = service.reportForAgent(AGENT_ID, 10).datasets().get(0);

        assertNotNull(dataset.noiseFloor());
        assertEquals(OLDER_RUN_ID, dataset.noiseFloor().firstRunId());
        assertEquals(NEWER_RUN_ID, dataset.noiseFloor().secondRunId());
        assertEquals(2, dataset.noiseFloor().pairedCases());
        assertEquals(0.1, dataset.noiseFloor().meanAbsoluteDelta(), 1e-9);
        assertEquals(0.0, dataset.noiseFloor().signedMeanDelta(), 1e-9);
        assertFalse(dataset.noiseFloor().configurationSuspect(),
            "opposite-direction drift on two cases is random variation, not a systematic change");
    }

    @Test
    void aSingleRunReportsNoNoiseFloorAndSaysWhy() {
        EvalRun only = run(NEWER_RUN_ID, 0.70, "judge-a");
        withDatasetAndRuns(List.of(only));
        withResults(NEWER_RUN_ID, scored(CASE_1, 0, 0.9), scored(CASE_2, 1, 0.5));

        DatasetReliabilityResponse dataset = service.reportForAgent(AGENT_ID, 10).datasets().get(0);

        assertNull(dataset.noiseFloor(), "an unmeasured floor must be absent, never zero");
        assertTrue(dataset.unreplicated());
        assertTrue(dataset.warnings().stream().anyMatch(w -> w.contains("No noise floor")),
            "the absence must be explained: " + dataset.warnings());
    }

    @Test
    void differentJudgeModelsAcrossRunsAreWarnedAbout() {
        EvalRun older = run(OLDER_RUN_ID, 0.70, "judge-a");
        EvalRun newer = run(NEWER_RUN_ID, 0.70, "judge-b");
        withDatasetAndRuns(List.of(newer, older));
        withResults(OLDER_RUN_ID, scored(CASE_1, 0, 0.9));
        withResults(NEWER_RUN_ID, scored(CASE_1, 0, 0.8));

        DatasetReliabilityResponse dataset = service.reportForAgent(AGENT_ID, 10).datasets().get(0);

        assertTrue(
            dataset.warnings().stream().anyMatch(w -> w.contains("DIFFERENT judge models")),
            "a judge swap between runs must not be invisible inside a score delta: "
                + dataset.warnings());
    }

    @Test
    void differentFrozenThresholdsAcrossRunsAreWarnedAbout() {
        EvalRun older = run(OLDER_RUN_ID, 0.60, "judge-a");
        EvalRun newer = run(NEWER_RUN_ID, 0.80, "judge-a");
        withDatasetAndRuns(List.of(newer, older));
        withResults(OLDER_RUN_ID, scored(CASE_1, 0, 0.7));
        withResults(NEWER_RUN_ID, scored(CASE_1, 0, 0.7));

        DatasetReliabilityResponse dataset = service.reportForAgent(AGENT_ID, 10).datasets().get(0);

        // Identical score, different bar: one rollout passes and one fails.
        assertEquals(1, caseRow(dataset, CASE_1).successes());
        assertEquals(2, caseRow(dataset, CASE_1).rollouts());
        assertTrue(
            dataset.warnings().stream().anyMatch(w -> w.contains("DIFFERENT pass thresholds")));
    }

    // ------------------------------------------------------------------
    // Empty states
    // ------------------------------------------------------------------

    @Test
    void anAgentWithNoDatasetsGetsAnEmptyReportWithAnExplanation() {
        when(datasetRepository.findByAgentIdOrderByCreatedAtDesc(AGENT_ID)).thenReturn(List.of());

        AgentReliabilityResponse report = service.reportForAgent(AGENT_ID, 10);

        assertTrue(report.datasets().isEmpty());
        assertFalse(report.warnings().isEmpty());
        assertEquals(0.95, report.confidenceLevel());
    }

    @Test
    void runsWhoseEveryCaseWasUnavailableProduceNoStatisticsRatherThanZeroes() {
        EvalRun only = run(NEWER_RUN_ID, 0.70, null);
        withDatasetAndRuns(List.of(only));
        withResults(NEWER_RUN_ID, unavailable(CASE_1, 0), unavailable(CASE_2, 1));

        DatasetReliabilityResponse dataset = service.reportForAgent(AGENT_ID, 10).datasets().get(0);

        assertEquals(0, dataset.caseCount());
        assertTrue(dataset.metrics().isEmpty(),
            "nothing was measured, so no metric may be reported - not even a zero");
        assertTrue(dataset.warnings().stream().anyMatch(w -> w.contains("not a score of zero")));
    }

    @Test
    void theRunWindowIsClampedToTheDocumentedMaximum() {
        when(datasetRepository.findByAgentIdOrderByCreatedAtDesc(AGENT_ID)).thenReturn(List.of());

        assertEquals(
            EvalReliabilityService.MAX_RUNS_PER_DATASET,
            service.reportForAgent(AGENT_ID, 10_000).maxRunsPerDataset());
        assertEquals(1, service.reportForAgent(AGENT_ID, -5).maxRunsPerDataset());
    }

    // ------------------------------------------------------------------
    // Standalone noise floor
    // ------------------------------------------------------------------

    @Test
    void theStandaloneNoiseFloorMatchesTheOneEmbeddedInTheReport() {
        EvalDataset dataset = dataset();
        EvalRun older = run(OLDER_RUN_ID, 0.70, "judge-a");
        EvalRun newer = run(NEWER_RUN_ID, 0.70, "judge-a");
        when(datasetRepository.findById(DATASET_ID)).thenReturn(Optional.of(dataset));
        when(runRepository.findByDatasetIdAndStatusOrderByCreatedAtDesc(
            eq(DATASET_ID), eq(EvalRunStatus.SUCCEEDED), any(Pageable.class)))
            .thenReturn(List.of(newer, older));
        withResults(OLDER_RUN_ID, scored(CASE_1, 0, 0.9), scored(CASE_2, 1, 0.5));
        withResults(NEWER_RUN_ID, scored(CASE_1, 0, 0.8), scored(CASE_2, 1, 0.6));

        DatasetNoiseFloorResponse response = service.noiseFloorForDataset(AGENT_ID, DATASET_ID);

        assertEquals(DATASET_ID, response.datasetId());
        assertEquals("Golden set", response.datasetName());
        assertNotNull(response.noiseFloor());
        assertEquals(0.1, response.noiseFloor().meanAbsoluteDelta(), 1e-9);
        assertEquals(2, response.noiseFloor().pairedCases());
    }

    @Test
    void aStandaloneNoiseFloorForAnotherAgentsDatasetIsNotFound() {
        EvalDataset foreign = mock(EvalDataset.class);
        when(foreign.getAgentId()).thenReturn(UUID.randomUUID());
        when(datasetRepository.findById(DATASET_ID)).thenReturn(Optional.of(foreign));

        assertThrows(
            EvalDatasetNotFoundException.class,
            () -> service.noiseFloorForDataset(AGENT_ID, DATASET_ID));
    }

    @Test
    void aStandaloneNoiseFloorForAMissingDatasetIsNotFound() {
        when(datasetRepository.findById(DATASET_ID)).thenReturn(Optional.empty());

        assertThrows(
            EvalDatasetNotFoundException.class,
            () -> service.noiseFloorForDataset(AGENT_ID, DATASET_ID));
    }

    // ------------------------------------------------------------------
    // Paired comparison guards
    // ------------------------------------------------------------------

    @Test
    void comparingARunWithItselfIsRefused() {
        assertThrows(
            IllegalArgumentException.class,
            () -> service.compareRuns(AGENT_ID, NEWER_RUN_ID, NEWER_RUN_ID));
    }

    @Test
    void aRunBelongingToAnotherAgentIsReportedAsNotFound() {
        EvalRun foreign = run(OLDER_RUN_ID, 0.70, "judge-a");
        EvalRun mine = run(NEWER_RUN_ID, 0.70, "judge-a");
        when(foreign.getAgentId()).thenReturn(UUID.randomUUID());
        when(runRepository.findById(OLDER_RUN_ID)).thenReturn(Optional.of(foreign));
        when(runRepository.findById(NEWER_RUN_ID)).thenReturn(Optional.of(mine));

        assertThrows(
            EvalRunNotFoundException.class,
            () -> service.compareRuns(AGENT_ID, OLDER_RUN_ID, NEWER_RUN_ID));
    }

    @Test
    void aRunThatIsNotSucceededIsReportedAsNotFound() {
        EvalRun live = run(OLDER_RUN_ID, 0.70, "judge-a");
        when(live.getStatus()).thenReturn(EvalRunStatus.RUNNING);
        when(runRepository.findById(OLDER_RUN_ID)).thenReturn(Optional.of(live));

        assertThrows(
            EvalRunNotFoundException.class,
            () -> service.compareRuns(AGENT_ID, OLDER_RUN_ID, NEWER_RUN_ID));
    }

    @Test
    void twoRunsOfDifferentDatasetsCannotBeCompared() {
        EvalRun baseline = run(OLDER_RUN_ID, 0.70, "judge-a");
        EvalRun variant = run(NEWER_RUN_ID, 0.70, "judge-a");
        when(variant.getDatasetId()).thenReturn(UUID.randomUUID());
        when(runRepository.findById(OLDER_RUN_ID)).thenReturn(Optional.of(baseline));
        when(runRepository.findById(NEWER_RUN_ID)).thenReturn(Optional.of(variant));

        assertThrows(
            EvalRunNotFoundException.class,
            () -> service.compareRuns(AGENT_ID, OLDER_RUN_ID, NEWER_RUN_ID));
    }

    /**
     * A real comparison: six cases, the variant better on every one by 0.10, and
     * no third pair of runs from which to measure an independent noise floor. The
     * verdict may name a winner, but the response must say the floor was missing
     * rather than implying the effect was tested against one.
     */
    @Test
    void aPairedComparisonWithNoIndependentFloorSaysSo() {
        EvalRun baseline = run(OLDER_RUN_ID, 0.70, "judge-a");
        EvalRun variant = run(NEWER_RUN_ID, 0.70, "judge-a");
        when(runRepository.findById(OLDER_RUN_ID)).thenReturn(Optional.of(baseline));
        when(runRepository.findById(NEWER_RUN_ID)).thenReturn(Optional.of(variant));
        when(runRepository.findByDatasetIdAndStatusOrderByCreatedAtDesc(
            eq(DATASET_ID), eq(EvalRunStatus.SUCCEEDED), any(Pageable.class)))
            .thenReturn(List.of(variant, baseline));

        List<EvalRunCaseResult> baselineResults = List.of(
            scored(CASE_1, 0, 0.30), scored(CASE_2, 1, 0.40), scored(CASE_3, 2, 0.50));
        List<EvalRunCaseResult> variantResults = List.of(
            scored(CASE_1, 0, 0.40), scored(CASE_2, 1, 0.50), scored(CASE_3, 2, 0.60));
        when(caseResultRepository.findByRunIdOrderByPositionAscCreatedAtAsc(OLDER_RUN_ID))
            .thenReturn(baselineResults);
        when(caseResultRepository.findByRunIdOrderByPositionAscCreatedAtAsc(NEWER_RUN_ID))
            .thenReturn(variantResults);

        PairedComparisonResponse comparison =
            service.compareRuns(AGENT_ID, OLDER_RUN_ID, NEWER_RUN_ID);

        assertEquals(3, comparison.pairedCases());
        assertEquals(0.10, comparison.meanDelta(), 1e-9);
        assertEquals(3, comparison.casesVariantBetter());
        assertEquals(ComparisonVerdict.VARIANT_BETTER, comparison.verdict());
        assertNull(comparison.noiseFloorTested(),
            "the only two runs available are the two being compared, so no independent floor exists");
        assertTrue(
            comparison.warnings().stream().anyMatch(w -> w.contains("No independent noise floor")),
            "a missing floor must be stated, not passed over: " + comparison.warnings());
    }
}
