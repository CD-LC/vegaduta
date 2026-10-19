package com.agenticai.core.quality.stats;

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
import com.agenticai.core.quality.stats.dto.ReliabilityDtos.IntervalResponse;
import com.agenticai.core.quality.stats.dto.ReliabilityDtos.MetricResponse;
import com.agenticai.core.quality.stats.dto.ReliabilityDtos.NoiseFloorResponse;
import com.agenticai.core.quality.stats.dto.ReliabilityDtos.PairedComparisonResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Turns the eval history this module already stores into honest statistics:
 * every score with a repeat count and an interval, an A/B that is allowed to
 * say "cannot tell", and a per-dataset measurement of the platform's own noise.
 *
 * <h2>Why this service reconstructs rollouts instead of adding a table</h2>
 * The proper shape for repeats is a first-class {@code eval_rollout} row per
 * (run, case, rollout index) with its own seed, as the build agenda describes -
 * then a single triggered run can execute k rollouts per case and the vector is
 * stored, not inferred. That needs a Flyway migration and a change to the run
 * executor, both of which live in files this workstream does not own, and it
 * would leave the platform with zero replicated data until the first new run
 * lands. So this service reads the data that ALREADY EXISTS and treats each
 * SUCCEEDED run of a dataset as ONE ROLLOUT of every case in it. That is exactly
 * what those runs are: independent executions of the same cases, fresh session
 * per case, no shared conversation state.
 *
 * <p><strong>The honest limits of that reconstruction, stated because a caller
 * must not assume them away:</strong>
 * <ul>
 *   <li>Rollouts are as independent as separate runs are. They are not
 *       seed-controlled - nothing in {@code eval_run} records a seed - so the
 *       reported intervals describe the variability the platform actually
 *       produced, not the variability of a controlled experiment.</li>
 *   <li>Nothing guarantees the configuration was unchanged between runs.
 *       {@code eval_run} snapshots the agent id and pass threshold but NOT an
 *       agent version, model or scaffold version, and agents are editable in
 *       place. Where the stored columns CAN reveal drift - a different
 *       {@code judgeModel}, a different frozen {@code passThreshold} - this
 *       service emits a warning rather than silently pooling incomparable
 *       rollouts. Where they cannot (a prompt edit between runs), the
 *       signed-delta self-check on the noise floor is the only available
 *       tripwire, and {@link NoiseFloor#configurationSuspect()} carries it.</li>
 *   <li>Replication is ragged. A case added last week has fewer rollouts than
 *       one that has been in the dataset for months, and pass@k / pass^k are
 *       therefore computed at the MINIMUM rollout count across cases - see
 *       {@link ReliabilityStats} for why that, and not the mean or the max.</li>
 * </ul>
 * Every one of those limits is surfaced in the response rather than buried here.
 *
 * <h2>How a rollout's pass/fail is decided</h2>
 * From the stored {@code eval_run_case_result}, matching the verdict rules
 * {@code EvalRunService} already documents so two parts of this module cannot
 * disagree about what "passed" means:
 * <ul>
 *   <li>{@code SCORED} - passes when its {@code overallScore} meets the run's
 *       FROZEN {@code passThreshold} AND no expected-tools assertion was
 *       violated. A tools violation fails the case regardless of score, exactly
 *       as it fails a run.</li>
 *   <li>{@code ERROR} - counts as a FAILED rollout. An agent that crashes on its
 *       golden case has failed it, whatever the reason.</li>
 *   <li>{@code UNAVAILABLE} - EXCLUDED from everything. No judge could measure
 *       it, so it is not evidence in either direction. Counting it as a failure
 *       would turn a judge-provider outage into an agent regression, which is
 *       the single most damaging thing this surface could get wrong, and is the
 *       same honest-absence contract {@code EvaluationResult} and
 *       {@code EvalRunVerdict.INCONCLUSIVE} enforce.</li>
 * </ul>
 * Continuous metrics (mean score) use only {@code SCORED} rollouts, because an
 * ERRORed replay produced no score and imputing a zero would corrupt the mean in
 * the flattering-to-nobody direction.
 *
 * <h2>Determinism</h2>
 * Bootstrap intervals are random quantities. Every seed here is derived from the
 * IDENTITY of the data being summarised (dataset id plus the contributing run
 * ids), never from a clock or a counter, so reloading the console renders
 * byte-identical intervals until the underlying runs change. An interval that
 * flickers on refresh is an interval nobody believes.
 */
@Service
public class EvalReliabilityService {

    private static final Logger log = LoggerFactory.getLogger(EvalReliabilityService.class);

    /**
     * How many recent SUCCEEDED runs of a dataset contribute rollouts by
     * default. Ten is generous relative to the agenda's default of k=5 while
     * still being a recent window - reliability should describe the agent as it
     * is now, and a window that reaches back far enough to span prompt rewrites
     * averages over agents that no longer exist.
     */
    public static final int DEFAULT_MAX_RUNS_PER_DATASET = 10;

    /**
     * Hard cap on the window. Each run in the window costs one case-result query
     * per dataset, and beyond a few dozen runs the statistics stop improving
     * while the staleness problem above gets worse.
     */
    public static final int MAX_RUNS_PER_DATASET = 50;

    /** Every interval on this surface is a 95% interval unless that changes here. */
    public static final double CONFIDENCE_LEVEL = WilsonInterval.DEFAULT_CONFIDENCE_LEVEL;

    /**
     * When true, a case result that carries a trajectory dimension counts as passed or failed by its persisted
     * case verdict (which already folds the trajectory's pass/fail in), so pass@k, pass^k and the Wilson
     * intervals see trajectory failures. Default false = the rule below, unchanged
     * ({@code app.quality.trajectory-eval.enabled}). Field-injected so the constructor stays as tests build it.
     */
    @org.springframework.beans.factory.annotation.Value("${app.quality.trajectory-eval.enabled:false}")
    boolean trajectoryEvalEnabled = false;

    private final EvalStatsRunRepository runRepository;
    private final EvalRunCaseResultRepository caseResultRepository;
    private final EvalDatasetRepository datasetRepository;
    private final EvalNoiseFloors noiseFloors;

    public EvalReliabilityService(
        EvalStatsRunRepository runRepository,
        EvalRunCaseResultRepository caseResultRepository,
        EvalDatasetRepository datasetRepository
    ) {
        this.runRepository = runRepository;
        this.caseResultRepository = caseResultRepository;
        this.datasetRepository = datasetRepository;
        this.noiseFloors = new EvalNoiseFloors(runRepository, caseResultRepository);
    }

    // ------------------------------------------------------------------
    // Agent-level reliability report
    // ------------------------------------------------------------------

    /**
     * Reliability for every dataset of one agent that has at least one SUCCEEDED
     * run.
     *
     * <p>Read-only and transactional: a report walks one dataset query plus one
     * case-result query per contributing run, and all of them must see the same
     * snapshot or a run that finishes mid-report would appear in the run list
     * with no case results.
     *
     * @param agentId the agent to report on; no ownership check here, matching
     *     the rest of this module (see {@code EvalDatasetService}'s javadoc for
     *     the boundary story) - tenant isolation comes from schema-per-tenant
     *     routing, and the plan gate lives in the controller
     * @param maxRunsPerDataset run-window size; clamped into
     *     {@code [1, MAX_RUNS_PER_DATASET]}
     */
    @Transactional(readOnly = true)
    public AgentReliabilityResponse reportForAgent(UUID agentId, int maxRunsPerDataset) {
        int window = Math.max(1, Math.min(maxRunsPerDataset, MAX_RUNS_PER_DATASET));
        List<EvalDataset> datasets = datasetRepository.findByAgentIdOrderByCreatedAtDesc(agentId);
        List<DatasetReliabilityResponse> reports = new ArrayList<>();
        for (EvalDataset dataset : datasets) {
            List<EvalRun> runs = noiseFloors.successfulRuns(dataset.getId(), window);
            if (runs.isEmpty()) {
                continue;
            }
            reports.add(describeDataset(dataset, runs));
        }

        List<String> warnings = new ArrayList<>();
        if (datasets.isEmpty()) {
            warnings.add(
                "This agent has no eval datasets, so there is nothing to measure reliability over. "
                    + "Create a dataset and run it at least twice - a single run is not a measurement.");
        } else if (reports.isEmpty()) {
            warnings.add(
                "This agent has " + datasets.size() + " eval dataset(s) but no SUCCEEDED runs yet, so no "
                    + "reliability statistics can be computed.");
        }
        return new AgentReliabilityResponse(
            agentId, Instant.now(), CONFIDENCE_LEVEL, window, reports, warnings);
    }

    /**
     * Builds the per-dataset report from a newest-first window of SUCCEEDED runs.
     */
    private DatasetReliabilityResponse describeDataset(EvalDataset dataset, List<EvalRun> newestFirst) {
        List<UUID> runIds = newestFirst.stream().map(EvalRun::getId).toList();
        long seed = ReliabilitySupport.seedFrom(dataset.getId(), runIds);

        // Oldest-first, so each case's outcome vector reads chronologically -
        // a reviewer looking at [false, false, true] should be seeing an agent
        // that got better, not one that got worse.
        List<EvalRun> chronological = new ArrayList<>(newestFirst);
        Collections.reverse(chronological);

        Map<String, CaseAccumulator> byCase = new LinkedHashMap<>();
        for (EvalRun run : chronological) {
            for (EvalRunCaseResult result
                : caseResultRepository.findByRunIdOrderByPositionAscCreatedAtAsc(run.getId())) {
                accumulate(byCase, run, result);
            }
        }

        List<CaseRollouts> rollouts = new ArrayList<>();
        List<CaseMeasurements> measurements = new ArrayList<>();
        List<CaseReliabilityResponse> caseResponses = new ArrayList<>();
        for (CaseAccumulator accumulator : byCase.values()) {
            if (accumulator.outcomes.isEmpty()) {
                // Every rollout of this case was UNAVAILABLE - nothing was
                // measured, so the case is absent from the statistics rather
                // than present with a fabricated outcome.
                continue;
            }
            CaseRollouts caseRollouts = new CaseRollouts(accumulator.caseKey, accumulator.outcomes);
            rollouts.add(caseRollouts);
            Double meanScore = null;
            if (!accumulator.scores.isEmpty()) {
                CaseMeasurements caseMeasurements =
                    new CaseMeasurements(accumulator.caseKey, accumulator.scores);
                measurements.add(caseMeasurements);
                meanScore = caseMeasurements.mean();
            }
            caseResponses.add(new CaseReliabilityResponse(
                accumulator.caseKey,
                accumulator.userMessage,
                caseRollouts.rollouts(),
                caseRollouts.successes(),
                caseRollouts.passRate(),
                ReliabilitySupport.toDto(WilsonInterval.forProportion(
                    caseRollouts.successes(), caseRollouts.rollouts(), CONFIDENCE_LEVEL)),
                caseRollouts.allPassed(),
                caseRollouts.unreplicated(),
                meanScore,
                accumulator.scores.size()));
        }

        List<String> warnings = new ArrayList<>();
        collectProvenanceWarnings(newestFirst, warnings);

        if (rollouts.isEmpty()) {
            warnings.add(
                "These runs completed but nothing in them was measurable (every case UNAVAILABLE - "
                    + "typically no judge provider was configured or reachable), so no reliability "
                    + "statistics can be computed. This is an absent measurement, not a score of zero.");
            return new DatasetReliabilityResponse(
                dataset.getId(), dataset.getName(), runIds, runIds.size(),
                0, 0, 0, 0, true, 0.0, List.of(), List.of(), null, warnings);
        }

        ReliabilityStats.SuiteReliability suite =
            ReliabilityStats.summarize(rollouts, CONFIDENCE_LEVEL, Bootstrap.DEFAULT_RESAMPLES, seed);
        if (suite.minRolloutsPerCase() != suite.maxRolloutsPerCase()) {
            warnings.add(String.format(Locale.ROOT,
                "Replication is ragged: cases have between %d and %d rollouts, so pass@k and pass^k are "
                    + "reported at k=%d - the most the least-replicated case can support. Rerun the "
                    + "dataset to level it up.",
                suite.minRolloutsPerCase(), suite.maxRolloutsPerCase(), suite.k()));
        }
        if (suite.unreplicated()) {
            warnings.add(
                "At least one case has only one rollout, so k=1 and nothing here is a reliability "
                    + "measurement - pass^1 is just pass@1. Run this dataset again (five times is the "
                    + "usual bar) before quoting any of these numbers.");
        }

        NoiseFloorResponse noiseFloor = noiseFloors.measure(newestFirst, warnings);

        return new DatasetReliabilityResponse(
            dataset.getId(),
            dataset.getName(),
            runIds,
            runIds.size(),
            suite.cases(),
            suite.minRolloutsPerCase(),
            suite.maxRolloutsPerCase(),
            suite.k(),
            suite.unreplicated(),
            suite.reliabilityGap(),
            buildMetrics(suite, measurements, seed),
            caseResponses,
            noiseFloor,
            warnings);
    }

    private void accumulate(
        Map<String, CaseAccumulator> byCase, EvalRun run, EvalRunCaseResult result
    ) {
        if (result.getStatus() == EvalCaseResultStatus.UNAVAILABLE) {
            // Not evidence in either direction - see this class's javadoc.
            return;
        }
        CaseAccumulator accumulator = byCase.computeIfAbsent(
            ReliabilitySupport.caseKey(result), key -> new CaseAccumulator(key, result.getUserMessage()));
        if (result.getStatus() == EvalCaseResultStatus.ERROR) {
            accumulator.outcomes.add(Boolean.FALSE);
            return;
        }
        Double score = result.getOverallScore();
        if (score == null) {
            // SCORED with a null aggregate should be impossible (the factory
            // sets UNAVAILABLE in that case), but treating it as a failure would
            // invent a measurement, so it is skipped and logged instead.
            log.warn("Eval run {} case {} is SCORED with no overall score - skipping the rollout",
                run.getId(), result.getId());
            return;
        }
        boolean toolsViolated = Boolean.FALSE.equals(result.getExpectedToolsSatisfied());
        boolean passed = !toolsViolated && score >= run.getPassThreshold();
        if (trajectoryEvalEnabled && result.getPassed() != null
            && com.agenticai.core.quality.trajectory.TrajectoryCaseGrader.hasTrajectoryDimension(result.getScoresJson())) {
            passed = result.getPassed();
        }
        accumulator.outcomes.add(passed);
        accumulator.scores.add(score);
    }

    private List<MetricResponse> buildMetrics(
        ReliabilityStats.SuiteReliability suite, List<CaseMeasurements> measurements, long seed
    ) {
        List<MetricResponse> metrics = new ArrayList<>();
        metrics.add(new MetricResponse(
            ReliabilityMetricId.PASS_AT_1,
            ReliabilityMetricId.PASS_AT_1.label(),
            ReliabilityMetricId.PASS_AT_1.question(),
            suite.passAtOne(),
            ReliabilitySupport.toDto(suite.passAtOneInterval()),
            suite.rollouts(),
            SampleUnit.ROLLOUT,
            null,
            suite.unreplicated()));
        metrics.add(new MetricResponse(
            ReliabilityMetricId.PASS_AT_K,
            ReliabilityMetricId.PASS_AT_K.label(),
            ReliabilityMetricId.PASS_AT_K.question(),
            suite.passAtK(),
            ReliabilitySupport.toDto(suite.passAtKInterval()),
            suite.cases(),
            SampleUnit.CASE,
            suite.k(),
            suite.unreplicated()));
        metrics.add(new MetricResponse(
            ReliabilityMetricId.PASS_HAT_K,
            ReliabilityMetricId.PASS_HAT_K.label(),
            ReliabilityMetricId.PASS_HAT_K.question(),
            suite.passHatK(),
            ReliabilitySupport.toDto(suite.passHatKInterval()),
            suite.cases(),
            SampleUnit.CASE,
            suite.k(),
            suite.unreplicated()));

        Double meanScore = null;
        IntervalResponse meanScoreInterval = null;
        boolean meanScoreUnreplicated = true;
        if (!measurements.isEmpty()) {
            meanScore = Bootstrap.meanOfCaseMeans(measurements);
            meanScoreUnreplicated = measurements.stream().anyMatch(CaseMeasurements::unreplicated);
            if (measurements.size() >= Bootstrap.MIN_CLUSTERS_FOR_INTERVAL) {
                // A different seed from the binomial metrics on purpose: these
                // are independent resampling exercises, and sharing a stream
                // would make two unrelated intervals move together when the
                // window changes, which looks like a correlation that is not
                // there.
                meanScoreInterval = ReliabilitySupport.toDto(Bootstrap.hierarchicalMeanOfCaseMeans(
                    measurements, Bootstrap.DEFAULT_RESAMPLES, CONFIDENCE_LEVEL, seed + 1L));
            }
        }
        metrics.add(new MetricResponse(
            ReliabilityMetricId.MEAN_SCORE,
            ReliabilityMetricId.MEAN_SCORE.label(),
            ReliabilityMetricId.MEAN_SCORE.question(),
            meanScore,
            meanScoreInterval,
            measurements.size(),
            SampleUnit.CASE,
            null,
            meanScoreUnreplicated));
        return metrics;
    }

    // ------------------------------------------------------------------
    // Noise floor (measured by EvalNoiseFloors)
    // ------------------------------------------------------------------

    /**
     * The noise floor for ONE dataset, on its own.
     *
     * <p>Same measurement as the {@code noiseFloor} field of that dataset's
     * reliability report and computed by the same code path - a console printing
     * "this suite's floor" beside an A/B verdict should not have to fetch and
     * discard a whole report to get one number.
     *
     * @throws EvalDatasetNotFoundException when the dataset does not exist or
     *     belongs to another agent - NOT FOUND rather than FORBIDDEN for the
     *     existence-leak reason {@code EvalDatasetService#requireDataset}
     *     documents
     */
    @Transactional(readOnly = true)
    public DatasetNoiseFloorResponse noiseFloorForDataset(UUID agentId, UUID datasetId) {
        EvalDataset dataset = datasetRepository.findById(datasetId)
            .orElseThrow(() -> new EvalDatasetNotFoundException(datasetId));
        if (!dataset.getAgentId().equals(agentId)) {
            throw new EvalDatasetNotFoundException(datasetId);
        }
        List<EvalRun> runs = noiseFloors.successfulRuns(datasetId, MAX_RUNS_PER_DATASET);
        List<String> warnings = new ArrayList<>();
        collectProvenanceWarnings(runs.size() > 2 ? runs.subList(0, 2) : runs, warnings);
        NoiseFloorResponse floor = noiseFloors.measure(runs, warnings);
        return new DatasetNoiseFloorResponse(datasetId, dataset.getName(), floor, warnings);
    }

    // ------------------------------------------------------------------
    // Paired A/B
    // ------------------------------------------------------------------

    /**
     * Paired comparison of two SUCCEEDED runs of the SAME dataset.
     *
     * <p>Pairing is by case, so the comparison is immune to which cases the
     * dataset happens to contain - see {@link PairedComparison} for the variance
     * arithmetic that makes this so much stronger than comparing the two runs'
     * separate intervals (which is what a reader does by eye today, and gets
     * wrong).
     *
     * <p>The noise floor used for the verdict comes from a DIFFERENT pair of
     * runs than the two being compared. Using the compared pair would be
     * circular - the same two runs cannot be both the "unchanged" control and
     * the "changed" experiment - and would let any effect clear its own floor by
     * construction. When no independent pair exists the floor is reported as
     * null and the verdict rests on the interval alone, with a warning saying so.
     *
     * @throws EvalRunNotFoundException when either run is missing, is not
     *     SUCCEEDED, does not belong to {@code agentId}, or the two belong to
     *     different datasets. All four are reported as NOT FOUND rather than
     *     FORBIDDEN/BAD REQUEST, mirroring
     *     {@code EvalDatasetService#requireDataset}: revealing "exists, but
     *     under a different agent" leaks existence across deep links for no
     *     benefit
     * @throws IllegalArgumentException (400) when the same run id is given twice
     */
    @Transactional(readOnly = true)
    public PairedComparisonResponse compareRuns(UUID agentId, UUID baselineRunId, UUID variantRunId) {
        if (baselineRunId.equals(variantRunId)) {
            throw new IllegalArgumentException(
                "baselineRunId and variantRunId are the same run (" + baselineRunId + ") - comparing a run "
                    + "with itself yields a delta of exactly zero and measures nothing.");
        }
        EvalRun baseline = requireSucceededRun(agentId, baselineRunId);
        EvalRun variant = requireSucceededRun(agentId, variantRunId);
        if (!baseline.getDatasetId().equals(variant.getDatasetId())) {
            throw new EvalRunNotFoundException(variantRunId);
        }
        UUID datasetId = baseline.getDatasetId();

        Map<String, Double> baselineScores = noiseFloors.scoresByCase(baseline);
        Map<String, Double> variantScores = noiseFloors.scoresByCase(variant);
        List<PairedCaseOutcomes> pairs = new ArrayList<>();
        for (Map.Entry<String, Double> entry : baselineScores.entrySet()) {
            Double variantScore = variantScores.get(entry.getKey());
            if (variantScore != null) {
                pairs.add(PairedCaseOutcomes.of(entry.getKey(), entry.getValue(), variantScore));
            }
        }

        List<String> warnings = new ArrayList<>();
        collectProvenanceWarnings(List.of(baseline, variant), warnings);
        int droppedCases =
            baselineScores.size() + variantScores.size() - 2 * pairs.size();
        if (droppedCases > 0) {
            warnings.add(droppedCases + " case(s) were scorable in only one of the two runs and were "
                + "dropped: a half-paired case is not comparable, and including it would reintroduce "
                + "exactly the between-case variance pairing removes.");
        }
        if (pairs.isEmpty()) {
            warnings.add(
                "These two runs share no case that was scorable in both, so there is nothing to compare.");
            return new PairedComparisonResponse(
                datasetId, baselineRunId, variantRunId, 0, 0.0, 0.0, 0.0, null,
                ComparisonVerdict.INSUFFICIENT_DATA, null, 0, 0, 0, null, true,
                "No case was scorable in both runs, so no comparison is possible.",
                warnings);
        }

        NoiseFloor floor = noiseFloors.independent(datasetId, Set.of(baselineRunId, variantRunId));
        if (floor == null) {
            warnings.add(
                "No independent noise floor was available (it must come from two runs other than the two "
                    + "being compared, to avoid using the experiment as its own control), so this verdict "
                    + "rests on the confidence interval alone. A small but 'significant' effect may still "
                    + "be inside this platform's own run-to-run variation.");
        } else if (floor.configurationSuspect()) {
            warnings.add(
                "The runs used for the independent noise floor differ systematically, so the floor this "
                    + "verdict was tested against may itself be contaminated by drift.");
        }

        PairedComparisonResult result = PairedComparison.compare(
            pairs,
            CONFIDENCE_LEVEL,
            Bootstrap.DEFAULT_RESAMPLES,
            ReliabilitySupport.seedFrom(baselineRunId, variantRunId),
            floor);

        return new PairedComparisonResponse(
            datasetId,
            baselineRunId,
            variantRunId,
            result.pairedCases(),
            result.baselineMean(),
            result.variantMean(),
            result.meanDelta(),
            ReliabilitySupport.toDto(result.deltaInterval()),
            result.verdict(),
            result.noiseFloorTested(),
            result.casesVariantBetter(),
            result.casesBaselineBetter(),
            result.casesTied(),
            result.signTestPValue(),
            result.unreplicated(),
            result.explanation(),
            warnings);
    }

    private EvalRun requireSucceededRun(UUID agentId, UUID runId) {
        EvalRun run = runRepository.findById(runId)
            .orElseThrow(() -> new EvalRunNotFoundException(runId));
        if (!run.getAgentId().equals(agentId) || run.getStatus() != EvalRunStatus.SUCCEEDED) {
            throw new EvalRunNotFoundException(runId);
        }
        return run;
    }

    // ------------------------------------------------------------------
    // Provenance
    // ------------------------------------------------------------------

    /**
     * Warns when the runs being pooled or compared are not actually comparable
     * in the ways the stored columns can reveal.
     *
     * <p>Both checks below are cheap and both catch a real, silent failure mode.
     * {@code judgeModel} exists on {@code eval_run} precisely because
     * {@code JudgeChatClientFactory} takes the FIRST catalog row and a catalog
     * reorder therefore changes the judge without anybody touching the agent -
     * scores from two different judges are not repeats of one another. A changed
     * {@code passThreshold} means the pass/fail binarisation differs between
     * rollouts, so the pooled pass rate is over two different questions.
     */
    private void collectProvenanceWarnings(List<EvalRun> runs, List<String> warnings) {
        Set<String> judgeModels = new LinkedHashSet<>();
        Set<Double> thresholds = new LinkedHashSet<>();
        for (EvalRun run : runs) {
            if (run.getJudgeModel() != null) {
                judgeModels.add(run.getJudgeModel());
            }
            thresholds.add(run.getPassThreshold());
        }
        if (judgeModels.size() > 1) {
            warnings.add("These runs were scored by DIFFERENT judge models (" + judgeModels
                + "). Scores from different judges are not repeats of each other, so the intervals below "
                + "mix judge disagreement into what reads as agent variability.");
        }
        if (thresholds.size() > 1) {
            warnings.add("These runs froze DIFFERENT pass thresholds (" + thresholds
                + "), so their pass/fail outcomes answer slightly different questions. The mean-score "
                + "metric is unaffected; the pass@ and pass^ metrics should be read with care.");
        }
    }

    /** Mutable per-case collector used only while walking the run window. */
    private static final class CaseAccumulator {

        private final String caseKey;
        private final String userMessage;
        private final List<Boolean> outcomes = new ArrayList<>();
        private final List<Double> scores = new ArrayList<>();

        private CaseAccumulator(String caseKey, String userMessage) {
            this.caseKey = caseKey;
            this.userMessage = userMessage;
        }
    }
}
