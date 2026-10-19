package com.agenticai.core.quality.stats.dto;

import com.agenticai.core.quality.stats.ComparisonVerdict;
import com.agenticai.core.quality.stats.IntervalMethod;
import com.agenticai.core.quality.stats.ReliabilityMetricId;
import com.agenticai.core.quality.stats.SampleUnit;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Response records for the reliability-statistics REST surface
 * ({@code EvalReliabilityController}).
 *
 * <p>One file of small records rather than a dozen single-record files - the
 * same grouping trade {@code EvalDatasetDtos} already makes in this module, for
 * the same reasons: none of these carries behaviour, and every consumer imports
 * several at once. Entities never cross the controller boundary; these do.
 *
 * <p><strong>The contract these records encode.</strong> Every reported number
 * arrives with (a) the sample size it was computed from, (b) what that sample
 * size counts ({@link SampleUnit}), (c) its interval or an explicit null, (d)
 * which estimator produced that interval, and (e) an {@code unreplicated} flag
 * when the number rests on single observations. A console cannot accidentally
 * render one of these as a bare point estimate without deliberately dropping
 * fields.
 *
 * <p><strong>Nulls are meaningful and are never placeholders.</strong> A null
 * interval means "not computable from this data" (one case, one rollout), never
 * "zero width". A null {@code value} means the metric could not be measured at
 * all. A null {@code noiseFloor} means the floor has not been measured for this
 * dataset, which is NOT the same as a floor of zero - the same honest-absence
 * rule {@code EvaluationResult.UNAVAILABLE} and
 * {@code EvalRunVerdict.INCONCLUSIVE} enforce elsewhere in this module.
 */
public final class ReliabilityDtos {

    private ReliabilityDtos() {
        // Namespace holder - never instantiated.
    }

    /**
     * A confidence interval as the console sees it.
     *
     * @param low lower bound
     * @param high upper bound
     * @param confidenceLevel nominal coverage, e.g. {@code 0.95}
     * @param method which estimator produced it - constant names of
     *     {@link IntervalMethod}: {@code WILSON_SCORE},
     *     {@code PERCENTILE_BOOTSTRAP}, {@code HIERARCHICAL_BOOTSTRAP},
     *     {@code PAIRED_BOOTSTRAP}
     */
    public record IntervalResponse(
        double low,
        double high,
        double confidenceLevel,
        IntervalMethod method
    ) {
    }

    /**
     * One metric row.
     *
     * @param metric stable identifier - constant names of
     *     {@link ReliabilityMetricId}: {@code PASS_AT_1}, {@code PASS_AT_K},
     *     {@code PASS_HAT_K}, {@code MEAN_SCORE}
     * @param label short human label for a column header ({@code "pass^k"})
     * @param question the plain-English question this metric answers, for a
     *     tooltip - shipped from the server so every surface tells the same
     *     story about what the number means
     * @param value the point estimate, or null when unmeasurable
     * @param interval the interval, or null when not computable from this data
     * @param n sample size the interval was computed over
     * @param unit what {@code n} counts - {@code ROLLOUT}, {@code CASE} or
     *     {@code CASE_PAIR}
     * @param k the attempt count for the pass@k / pass^k rows; null for metrics
     *     where k is meaningless
     * @param unreplicated true when this number rests on single observations
     *     (k == 1 / one rollout per case). The console must badge it rather than
     *     present it as a reliability measurement
     */
    public record MetricResponse(
        ReliabilityMetricId metric,
        String label,
        String question,
        Double value,
        IntervalResponse interval,
        int n,
        SampleUnit unit,
        Integer k,
        boolean unreplicated
    ) {
    }

    /**
     * Per-case detail, so a reviewer can see WHICH case is unstable rather than
     * only that the suite is.
     *
     * @param caseKey stable case identifier - the {@code eval_dataset_case} id
     *     when the result row carried one, otherwise {@code "pos:<position>"}
     * @param userMessage the case's prompt snapshot, for display
     * @param rollouts how many runs contributed an outcome for this case
     * @param successes how many of those passed
     * @param passRate {@code successes / rollouts}
     * @param passRateInterval Wilson interval for {@code passRate}
     * @param allPassed whether every observed rollout passed
     * @param unreplicated whether this case was observed exactly once
     * @param meanScore mean judge score across the rollouts that produced one,
     *     or null when no rollout of this case was scorable
     * @param scoredRollouts how many rollouts contributed a judge score (can be
     *     lower than {@code rollouts}: an ERRORed replay counts as a failed
     *     rollout but produces no score)
     */
    public record CaseReliabilityResponse(
        String caseKey,
        String userMessage,
        int rollouts,
        int successes,
        double passRate,
        IntervalResponse passRateInterval,
        boolean allPassed,
        boolean unreplicated,
        Double meanScore,
        int scoredRollouts
    ) {
    }

    /**
     * The measured noise floor for one dataset.
     *
     * @param firstRunId the earlier of the two reruns compared
     * @param secondRunId the later of the two
     * @param secondRunFinishedAt when the later rerun finished - staleness is
     *     visible, because a floor measured before a provider migration is not
     *     this platform's floor any more
     * @param pairedCases cases measured in both reruns
     * @param meanAbsoluteDelta mean per-case absolute disagreement
     * @param maxAbsoluteDelta worst single-case disagreement
     * @param floor the bar an A/B effect must exceed: the upper end of
     *     {@code meanAbsoluteDeltaInterval} when available, else
     *     {@code maxAbsoluteDelta}
     * @param meanAbsoluteDeltaInterval interval for {@code meanAbsoluteDelta},
     *     or null with fewer than two paired cases
     * @param signedMeanDelta mean SIGNED disagreement - should be near zero for
     *     a genuine rerun
     * @param signedMeanDeltaInterval interval for {@code signedMeanDelta}
     * @param configurationSuspect true when {@code signedMeanDeltaInterval}
     *     excludes zero: the two "identical" runs differ systematically, so this
     *     is drift evidence rather than a clean floor and must be surfaced as
     *     such
     */
    public record NoiseFloorResponse(
        UUID firstRunId,
        UUID secondRunId,
        Instant secondRunFinishedAt,
        int pairedCases,
        double meanAbsoluteDelta,
        double maxAbsoluteDelta,
        double floor,
        IntervalResponse meanAbsoluteDeltaInterval,
        double signedMeanDelta,
        IntervalResponse signedMeanDeltaInterval,
        boolean configurationSuspect
    ) {
    }

    /**
     * Reliability for one dataset of one agent, reconstructed from its recent
     * runs.
     *
     * @param datasetId the dataset these numbers describe
     * @param datasetName its name at read time (not a snapshot - datasets are
     *     editable, and this is a display label only)
     * @param runIds the runs that contributed, newest first
     * @param runsConsidered {@code runIds.size()} - each SUCCEEDED run
     *     contributes at most one rollout per case
     * @param caseCount distinct cases with at least one usable rollout
     * @param minRolloutsPerCase least-replicated case's rollout count; equals
     *     {@code k}
     * @param maxRolloutsPerCase most-replicated case's rollout count; a wide gap
     *     between min and max means ragged replication
     * @param k the attempt count pass@k / pass^k were computed at - the MINIMUM
     *     rollout count across cases, because that is the largest k every case
     *     can honestly support
     * @param unreplicated true when {@code k == 1}
     * @param reliabilityGap {@code pass@1 - pass^k}: how much of the headline
     *     rate does not survive the requirement that it hold k times running
     * @param metrics the metric rows
     * @param cases per-case detail
     * @param noiseFloor this dataset's measured floor, or null when fewer than
     *     two runs exist to measure it from
     * @param warnings machine-generated, human-readable caveats that apply to
     *     these numbers (ragged replication, judge-model drift between runs,
     *     changed pass threshold). Never empty-as-decoration: if a warning is
     *     present it materially limits the numbers above it
     */
    public record DatasetReliabilityResponse(
        UUID datasetId,
        String datasetName,
        List<UUID> runIds,
        int runsConsidered,
        int caseCount,
        int minRolloutsPerCase,
        int maxRolloutsPerCase,
        int k,
        boolean unreplicated,
        double reliabilityGap,
        List<MetricResponse> metrics,
        List<CaseReliabilityResponse> cases,
        NoiseFloorResponse noiseFloor,
        List<String> warnings
    ) {
    }

    /**
     * The whole reliability report for one agent: one entry per dataset that has
     * at least one SUCCEEDED run.
     *
     * <p>Split by dataset rather than pooled because different datasets are
     * different case sets with different (frozen) pass thresholds - pooling them
     * would average over questions nobody asked together.
     *
     * @param agentId the agent
     * @param generatedAt when this report was computed
     * @param confidenceLevel the level every interval in this response uses
     * @param maxRunsPerDataset the run-window size that was applied
     * @param datasets per-dataset reliability, datasets newest-first by their
     *     most recent run
     * @param warnings report-level caveats (e.g. no runs at all)
     */
    public record AgentReliabilityResponse(
        UUID agentId,
        Instant generatedAt,
        double confidenceLevel,
        int maxRunsPerDataset,
        List<DatasetReliabilityResponse> datasets,
        List<String> warnings
    ) {
    }

    /**
     * The noise floor for ONE dataset, addressable on its own.
     *
     * <p>Exists in addition to the {@code noiseFloor} field on
     * {@link DatasetReliabilityResponse} because a console that wants to print
     * "this tenant's floor on this suite" beside an A/B should not have to fetch
     * and discard a whole reliability report to get it.
     *
     * @param datasetId the dataset the floor was measured on - a floor is only
     *     meaningful for the suite it came from, so it is always labelled
     * @param datasetName the dataset's name at read time, for display
     * @param noiseFloor the measured floor, or null when it could not be
     *     measured (fewer than two SUCCEEDED runs, or no case scorable in both)
     * @param warnings why the floor is absent or suspect, when it is
     */
    public record DatasetNoiseFloorResponse(
        UUID datasetId,
        String datasetName,
        NoiseFloorResponse noiseFloor,
        List<String> warnings
    ) {
    }

    /**
     * A paired A/B between two runs of the same dataset.
     *
     * @param datasetId the dataset both runs executed
     * @param baselineRunId the incumbent run
     * @param variantRunId the challenger run
     * @param pairedCases cases present in both runs
     * @param baselineMean unweighted mean of baseline per-case means
     * @param variantMean unweighted mean of variant per-case means
     * @param meanDelta mean per-case {@code variant - baseline}; positive
     *     favours the variant
     * @param deltaInterval paired bootstrap interval for {@code meanDelta}, or
     *     null with fewer than two paired cases
     * @param verdict the adjudication - constant names of
     *     {@link ComparisonVerdict}: {@code VARIANT_BETTER},
     *     {@code BASELINE_BETTER}, {@code CANNOT_TELL},
     *     {@code INSIDE_NOISE_FLOOR}, {@code INSUFFICIENT_DATA}. The console
     *     must render a winner badge ONLY for the first two
     * @param noiseFloorTested the floor the effect was tested against, or null
     *     when no independent floor was available (null is not a pass)
     * @param casesVariantBetter per-case direction counts
     * @param casesBaselineBetter per-case direction counts
     * @param casesTied per-case direction counts
     * @param signTestPValue exact two-sided sign-test p-value over discordant
     *     cases, or null when there are none
     * @param unreplicated true when any case had a single rollout in either arm
     * @param explanation one server-generated sentence stating the verdict and
     *     its reason - suitable for a tooltip or a decision memo
     * @param warnings caveats that limit this comparison (judge model differed,
     *     pass threshold differed, cases present in only one run were dropped)
     */
    public record PairedComparisonResponse(
        UUID datasetId,
        UUID baselineRunId,
        UUID variantRunId,
        int pairedCases,
        double baselineMean,
        double variantMean,
        double meanDelta,
        IntervalResponse deltaInterval,
        ComparisonVerdict verdict,
        Double noiseFloorTested,
        int casesVariantBetter,
        int casesBaselineBetter,
        int casesTied,
        Double signTestPValue,
        boolean unreplicated,
        String explanation,
        List<String> warnings
    ) {
    }
}
