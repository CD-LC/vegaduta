package com.agenticai.core.quality.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.quality.dataset.EvalDatasetCase;
import com.agenticai.core.quality.dataset.EvalRun;
import com.agenticai.core.quality.dataset.EvalRunCaseResult;
import com.agenticai.core.quality.dataset.EvalRunCaseResultRepository;
import com.agenticai.core.quality.dataset.EvalRunStatus;
import com.agenticai.core.quality.stats.dto.ReliabilityDtos.NoiseFloorResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The window read behind the reliability report: which runs count, how the two most recent reruns
 * pair case-by-case, and when the floor is honestly ABSENT (one run, or no case scored in both)
 * rather than silently zero.
 */
class EvalNoiseFloorsTest {

    private static final UUID DATASET = UUID.randomUUID();

    private EvalStatsRunRepository runRepository;
    private EvalRunCaseResultRepository caseResultRepository;
    private EvalNoiseFloors floors;

    @BeforeEach
    void setUp() {
        runRepository = mock(EvalStatsRunRepository.class);
        caseResultRepository = mock(EvalRunCaseResultRepository.class);
        floors = new EvalNoiseFloors(runRepository, caseResultRepository);
    }

    /** A run row carrying the id the repository would have assigned. */
    private static EvalRun run(UUID id) {
        EvalRun run = mock(EvalRun.class);
        when(run.getId()).thenReturn(id);
        when(run.getFinishedAt()).thenReturn(Instant.parse("2026-10-01T00:00:00Z"));
        return run;
    }

    private static EvalDatasetCase sourceCase(int position) {
        return new EvalDatasetCase(DATASET, "case-" + position, "expected", null, position);
    }

    /** A SCORED result; {@code caseId} null on purpose so the key is {@code pos:<position>}. */
    private static EvalRunCaseResult scored(UUID runId, int position, double score) {
        return EvalRunCaseResult.scored(
                runId, sourceCase(position), "answer", null, "{}", score, null);
    }

    private void stubRuns(List<EvalRun> runs) {
        when(runRepository.findByDatasetIdAndStatusOrderByCreatedAtDesc(
                        eq(DATASET), eq(EvalRunStatus.SUCCEEDED), any()))
                .thenReturn(runs);
    }

    private void stubScores(UUID runId, List<EvalRunCaseResult> results) {
        when(caseResultRepository.findByRunIdOrderByPositionAscCreatedAtAsc(runId))
                .thenReturn(results);
    }

    // ---------------------------------------------------------------- successfulRuns

    @Test
    void successfulRunsAskForTheNewestWindowOnly() {
        EvalRun run = run(UUID.randomUUID());
        stubRuns(List.of(run));

        assertEquals(List.of(run), floors.successfulRuns(DATASET, 5));
    }

    // ---------------------------------------------------------------- measure

    @Test
    void fewerThanTwoRunsMeansAnUnmeasuredFloorNotAZeroOne() {
        List<String> warnings = new ArrayList<>();

        assertNull(floors.measure(List.of(run(UUID.randomUUID())), warnings));
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("only one SUCCEEDED run"));
    }

    @Test
    void runsSharingNoScorableCaseProduceNoFloor() {
        EvalRun earlier = run(UUID.randomUUID());
        EvalRun later = run(UUID.randomUUID());
        stubScores(earlier.getId(), List.of(scored(earlier.getId(), 1, 0.5)));
        stubScores(later.getId(), List.of(scored(later.getId(), 9, 0.5)));
        List<String> warnings = new ArrayList<>();

        assertNull(floors.measure(List.of(later, earlier), warnings));
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("no case"));
    }

    @Test
    void aMeasuredFloorPairsOnlyCasesScoredInBothRuns() {
        EvalRun earlier = run(UUID.randomUUID());
        EvalRun later = run(UUID.randomUUID());
        stubScores(
                earlier.getId(),
                List.of(
                        scored(earlier.getId(), 1, 0.5),
                        scored(earlier.getId(), 2, 0.6),
                        EvalRunCaseResult.errored(earlier.getId(), sourceCase(3), "replay broke")));
        stubScores(
                later.getId(),
                List.of(
                        scored(later.getId(), 1, 0.7),
                        scored(later.getId(), 2, 0.5),
                        scored(later.getId(), 8, 0.9)));

        List<String> warnings = new ArrayList<>();
        NoiseFloorResponse floor = floors.measure(List.of(later, earlier), warnings);

        assertNotNull(floor);
        assertEquals(earlier.getId(), floor.firstRunId());
        assertEquals(later.getId(), floor.secondRunId());
        assertEquals(
                2,
                floor.pairedCases(),
                "only cases SCORED in both runs pair - errored and single-sided results are dropped");
        assertEquals(0.15, floor.meanAbsoluteDelta(), 1e-12);
        assertEquals(0.2, floor.maxAbsoluteDelta(), 1e-12);
        assertTrue(floor.floor() >= floor.meanAbsoluteDelta());
        assertTrue(warnings.isEmpty(), "a clean rerun pair produces no warnings: " + warnings);
    }

    @Test
    void systematicallyDifferentRunsAreFlaggedNotSilentlyTrusted() {
        EvalRun earlier = run(UUID.randomUUID());
        EvalRun later = run(UUID.randomUUID());
        List<EvalRunCaseResult> earlierScores = new ArrayList<>();
        List<EvalRunCaseResult> laterScores = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            earlierScores.add(scored(earlier.getId(), i, 0.5));
            laterScores.add(scored(later.getId(), i, 0.7));
        }
        stubScores(earlier.getId(), earlierScores);
        stubScores(later.getId(), laterScores);
        List<String> warnings = new ArrayList<>();

        NoiseFloorResponse floor = floors.measure(List.of(later, earlier), warnings);

        assertNotNull(floor);
        assertTrue(floor.configurationSuspect());
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("SYSTEMATICALLY"));
    }

    // ---------------------------------------------------------------- independent

    @Test
    void theIndependentFloorSkipsTheRunsBeingCompared() {
        EvalRun excludedA = run(UUID.randomUUID());
        EvalRun survivorA = run(UUID.randomUUID());
        EvalRun survivorB = run(UUID.randomUUID());
        stubRuns(List.of(excludedA, survivorA, survivorB));
        stubScores(survivorB.getId(), List.of(scored(survivorB.getId(), 1, 0.4)));
        stubScores(survivorA.getId(), List.of(scored(survivorA.getId(), 1, 0.6)));

        NoiseFloor floor = floors.independent(DATASET, Set.of(excludedA.getId()));

        assertNotNull(
                floor,
                "the two runs that remain once the compared run is excluded pair into a floor");
        assertEquals(1, floor.pairedCases());
        assertEquals(0.2, floor.meanAbsoluteDelta(), 1e-12);
    }

    @Test
    void fewerThanTwoSurvivingRunsMeansNoIndependentFloor() {
        EvalRun only = run(UUID.randomUUID());
        stubRuns(List.of(only));

        assertNull(
                floors.independent(DATASET, Set.of(only.getId())),
                "excluding the only run leaves nothing to pair");

        stubRuns(List.of(run(UUID.randomUUID())));
        assertNull(floors.independent(DATASET, Set.of()), "one run cannot be paired");
    }

    @Test
    void anIndependentFloorNeedsAPairableCase() {
        EvalRun a = run(UUID.randomUUID());
        EvalRun b = run(UUID.randomUUID());
        stubRuns(List.of(a, b));
        stubScores(b.getId(), List.of(scored(b.getId(), 1, 0.4)));
        stubScores(a.getId(), List.of(scored(a.getId(), 2, 0.4)));

        assertNull(floors.independent(DATASET, Set.of()));
    }

    // ---------------------------------------------------------------- scoresByCase

    @Test
    void onlyScoredResultsWithAMeasurementContributeACaseScore() {
        EvalRun run = run(UUID.randomUUID());
        // Hoisted before the stubbing: a mock call nested inside when(...)/thenReturn(...)
        // argument evaluation trips Mockito's unfinished-stubbing detection.
        UUID runId = run.getId();
        doReturn(
                        List.of(
                                scored(runId, 1, 0.5),
                                scored(runId, 2, 0.6),
                                // UNAVAILABLE: replay fine, no judge measurement -> not a score.
                                EvalRunCaseResult.scored(
                                        runId, sourceCase(3), "a", null, "{}", null, null),
                                EvalRunCaseResult.errored(runId, sourceCase(4), "boom")))
                .when(caseResultRepository)
                .findByRunIdOrderByPositionAscCreatedAtAsc(runId);

        assertEquals(java.util.Map.of("pos:1", 0.5, "pos:2", 0.6), floors.scoresByCase(run));
    }
}
