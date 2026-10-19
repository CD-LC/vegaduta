package com.agenticai.core.quality.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.quality.dataset.EvalCaseResultStatus;
import com.agenticai.core.quality.dataset.EvalDataset;
import com.agenticai.core.quality.dataset.EvalDatasetRepository;
import com.agenticai.core.quality.dataset.EvalRun;
import com.agenticai.core.quality.dataset.EvalRunCaseResult;
import com.agenticai.core.quality.dataset.EvalRunCaseResultRepository;
import com.agenticai.core.quality.dataset.EvalRunStatus;
import com.agenticai.core.quality.stats.dto.ReliabilityDtos.CaseReliabilityResponse;
import com.agenticai.core.quality.stats.dto.ReliabilityDtos.DatasetReliabilityResponse;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

/**
 * pass^k / Wilson inputs for trajectory-graded cases: behind {@code app.quality.trajectory-eval.enabled} a case
 * result carrying a TRAJECTORY dimension passes or fails by its persisted case verdict (which already folds the
 * trajectory in); with the flag off the rule is exactly the pre-existing one.
 */
class EvalReliabilityServiceTrajectoryTest {

    private static final UUID AGENT_ID = UUID.randomUUID();
    private static final UUID DATASET_ID = UUID.randomUUID();
    private static final UUID RUN_ID = UUID.randomUUID();
    private static final UUID CASE_FAILS_TRAJECTORY = UUID.randomUUID();
    private static final UUID CASE_PASSES_TRAJECTORY = UUID.randomUUID();
    private static final UUID CASE_PLAIN = UUID.randomUUID();

    private static final String WITH_TRAJECTORY =
        "{\"ACCURACY\":{\"score\":0.95,\"status\":\"EVALUATED\"},\"TRAJECTORY\":{\"score\":0.5,\"status\":\"EVALUATED\"}}";
    private static final String WITHOUT_TRAJECTORY = "{\"ACCURACY\":{\"score\":0.95,\"status\":\"EVALUATED\"}}";

    private EvalReliabilityService service;
    private EvalStatsRunRepository runRepository;
    private EvalRunCaseResultRepository caseResultRepository;
    private EvalDatasetRepository datasetRepository;

    @BeforeEach
    void setUp() {
        runRepository = mock(EvalStatsRunRepository.class);
        caseResultRepository = mock(EvalRunCaseResultRepository.class);
        datasetRepository = mock(EvalDatasetRepository.class);
        service = new EvalReliabilityService(runRepository, caseResultRepository, datasetRepository);
    }

    private static EvalRunCaseResult result(UUID caseId, int position, Boolean passed, String scoresJson) {
        EvalRunCaseResult r = mock(EvalRunCaseResult.class);
        when(r.getId()).thenReturn(UUID.randomUUID());
        when(r.getCaseId()).thenReturn(caseId);
        when(r.getPosition()).thenReturn(position);
        when(r.getStatus()).thenReturn(EvalCaseResultStatus.SCORED);
        when(r.getOverallScore()).thenReturn(0.95);
        when(r.getUserMessage()).thenReturn("case " + position);
        when(r.getExpectedToolsSatisfied()).thenReturn((Boolean) null);
        when(r.getPassed()).thenReturn(passed);
        when(r.getScoresJson()).thenReturn(scoresJson);
        return r;
    }

    private DatasetReliabilityResponse report() {
        EvalDataset dataset = mock(EvalDataset.class);
        when(dataset.getId()).thenReturn(DATASET_ID);
        when(dataset.getAgentId()).thenReturn(AGENT_ID);
        when(dataset.getName()).thenReturn("Golden set");
        EvalRun run = mock(EvalRun.class);
        when(run.getId()).thenReturn(RUN_ID);
        when(run.getAgentId()).thenReturn(AGENT_ID);
        when(run.getDatasetId()).thenReturn(DATASET_ID);
        when(run.getStatus()).thenReturn(EvalRunStatus.SUCCEEDED);
        when(run.getPassThreshold()).thenReturn(0.70);
        when(run.getJudgeModel()).thenReturn("judge-a");
        when(run.getFinishedAt()).thenReturn(Instant.parse("2026-09-10T00:00:00Z"));
        when(datasetRepository.findByAgentIdOrderByCreatedAtDesc(AGENT_ID)).thenReturn(List.of(dataset));
        when(runRepository.findByDatasetIdAndStatusOrderByCreatedAtDesc(
            eq(DATASET_ID), eq(EvalRunStatus.SUCCEEDED), any(Pageable.class))).thenReturn(List.of(run));
        EvalRunCaseResult failing = result(CASE_FAILS_TRAJECTORY, 0, Boolean.FALSE, WITH_TRAJECTORY);
        EvalRunCaseResult passing = result(CASE_PASSES_TRAJECTORY, 1, Boolean.TRUE, WITH_TRAJECTORY);
        EvalRunCaseResult plain = result(CASE_PLAIN, 2, Boolean.FALSE, WITHOUT_TRAJECTORY);
        when(caseResultRepository.findByRunIdOrderByPositionAscCreatedAtAsc(RUN_ID))
            .thenReturn(List.of(failing, passing, plain));
        return service.reportForAgent(AGENT_ID, 10).datasets().get(0);
    }

    private static CaseReliabilityResponse caseRow(DatasetReliabilityResponse dataset, UUID caseId) {
        return dataset.cases().stream()
            .filter(row -> row.caseKey().equals(caseId.toString()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("case " + caseId + " missing from the report"));
    }

    @Test
    void withTheFlagOnATrajectoryCaseFollowsItsPersistedVerdict() {
        service.trajectoryEvalEnabled = true;

        DatasetReliabilityResponse dataset = report();

        assertEquals(0, caseRow(dataset, CASE_FAILS_TRAJECTORY).successes(),
            "a failed trajectory fails the rollout even though the judge score cleared the bar");
        assertEquals(1, caseRow(dataset, CASE_PASSES_TRAJECTORY).successes());
        // No trajectory dimension: the original rule (score 0.95 >= 0.70, no tools violation) still decides,
        // whatever the stored 'passed' column says.
        assertEquals(1, caseRow(dataset, CASE_PLAIN).successes());
    }

    @Test
    void withTheFlagOffTheOriginalRuleDecidesEverything() {
        service.trajectoryEvalEnabled = false;

        DatasetReliabilityResponse dataset = report();

        assertEquals(1, caseRow(dataset, CASE_FAILS_TRAJECTORY).successes());
        assertEquals(1, caseRow(dataset, CASE_PASSES_TRAJECTORY).successes());
        assertEquals(1, caseRow(dataset, CASE_PLAIN).successes());
    }
}
