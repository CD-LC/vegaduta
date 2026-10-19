package com.agenticai.core.quality.stats;

import com.agenticai.core.quality.dataset.EvalCaseResultStatus;
import com.agenticai.core.quality.dataset.EvalRun;
import com.agenticai.core.quality.dataset.EvalRunCaseResult;
import com.agenticai.core.quality.dataset.EvalRunCaseResultRepository;
import com.agenticai.core.quality.dataset.EvalRunStatus;
import com.agenticai.core.quality.stats.dto.ReliabilityDtos.NoiseFloorResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;

/**
 * The run-window reads behind {@link EvalReliabilityService}: a dataset's recent SUCCEEDED runs, the per-case judge
 * scores of a run, the case-by-case pairing of two runs, and the noise floor measured from such a pair (either the
 * two most recent runs, or - for an A/B - the most recent pair that excludes the runs being compared). Split out of
 * the service, which keeps the reports and the A/B verdict; the service's read-only transaction covers every call.
 */
final class EvalNoiseFloors {

    private final EvalStatsRunRepository runRepository;
    private final EvalRunCaseResultRepository caseResultRepository;

    EvalNoiseFloors(EvalStatsRunRepository runRepository, EvalRunCaseResultRepository caseResultRepository) {
        this.runRepository = runRepository;
        this.caseResultRepository = caseResultRepository;
    }

    List<EvalRun> successfulRuns(UUID datasetId, int window) {
        return runRepository.findByDatasetIdAndStatusOrderByCreatedAtDesc(
            datasetId, EvalRunStatus.SUCCEEDED, PageRequest.of(0, window));
    }

    /**
     * Measures this dataset's noise floor from its two most recent SUCCEEDED
     * runs, on the assumption that they are reruns of the same configuration.
     *
     * <p>That assumption is the weak point and is treated as one: the stored
     * columns that CAN show a difference ({@code judgeModel},
     * {@code passThreshold}) are compared and warned about by
     * {@code EvalReliabilityService#collectProvenanceWarnings}, and the estimator's own signed-delta
     * self-check flags a systematic difference that the columns cannot see (a
     * prompt edit between the runs). A properly scheduled job that deliberately
     * reruns an untouched configuration - the agenda's {@code NoiseFloorJob} -
     * would remove the assumption entirely; that needs a scheduler and a write
     * path, so it is a recorded follow-up rather than something invented here.
     * What is here measures the same quantity from data that already exists.
     */
    NoiseFloorResponse measure(List<EvalRun> newestFirst, List<String> warnings) {
        if (newestFirst.size() < 2) {
            warnings.add(
                "No noise floor: this dataset has only one SUCCEEDED run in the window, so the platform's "
                    + "own run-to-run variation is unmeasured and no A/B effect size can be judged "
                    + "against it. An unmeasured floor is not a floor of zero.");
            return null;
        }
        EvalRun later = newestFirst.get(0);
        EvalRun earlier = newestFirst.get(1);
        List<PairedCaseOutcomes> pairs = pairScores(earlier, later);
        if (pairs.isEmpty()) {
            warnings.add(
                "No noise floor: the two most recent runs share no case that was scorable in both.");
            return null;
        }
        NoiseFloor floor = NoiseFloorEstimator.estimate(
            pairs,
            EvalReliabilityService.CONFIDENCE_LEVEL,
            Bootstrap.DEFAULT_RESAMPLES,
            ReliabilitySupport.seedFrom(earlier.getId(), later.getId()));
        if (floor.configurationSuspect()) {
            warnings.add(String.format(Locale.ROOT,
                "The two runs used for the noise floor differ SYSTEMATICALLY (signed mean delta %+.4f, "
                    + "interval excludes zero), so they were not reruns of an identical configuration - "
                    + "something changed between them (agent prompt, provider arm, or judge model). Treat "
                    + "this as drift evidence, not as a clean noise floor.",
                floor.signedMeanDelta()));
        }
        return new NoiseFloorResponse(
            earlier.getId(),
            later.getId(),
            later.getFinishedAt(),
            floor.pairedCases(),
            floor.meanAbsoluteDelta(),
            floor.maxAbsoluteDelta(),
            floor.floor(),
            ReliabilitySupport.toDto(floor.absoluteDeltaInterval()),
            floor.signedMeanDelta(),
            ReliabilitySupport.toDto(floor.signedDeltaInterval()),
            floor.configurationSuspect());
    }

    /**
     * The most recent pair of SUCCEEDED runs of this dataset EXCLUDING the given
     * run ids, as a noise floor. Null when fewer than two such runs exist.
     */
    NoiseFloor independent(UUID datasetId, Set<UUID> excludedRunIds) {
        List<EvalRun> candidates = new ArrayList<>();
        for (EvalRun run : successfulRuns(datasetId, EvalReliabilityService.MAX_RUNS_PER_DATASET)) {
            if (!excludedRunIds.contains(run.getId())) {
                candidates.add(run);
            }
            if (candidates.size() == 2) {
                break;
            }
        }
        if (candidates.size() < 2) {
            return null;
        }
        EvalRun later = candidates.get(0);
        EvalRun earlier = candidates.get(1);
        List<PairedCaseOutcomes> pairs = pairScores(earlier, later);
        if (pairs.isEmpty()) {
            return null;
        }
        return NoiseFloorEstimator.estimate(
            pairs,
            EvalReliabilityService.CONFIDENCE_LEVEL,
            Bootstrap.DEFAULT_RESAMPLES,
            ReliabilitySupport.seedFrom(earlier.getId(), later.getId()));
    }

    /**
     * Pairs two runs case by case on the judge score, keeping only cases that
     * were SCORED in BOTH. Judge-score units (not pass/fail) so that the noise
     * floor and the A/B delta it gates are measured on the same scale - a floor
     * in one unit gating an effect in another is a bug waiting for a reader who
     * does not notice.
     */
    private List<PairedCaseOutcomes> pairScores(EvalRun earlier, EvalRun later) {
        Map<String, Double> earlierScores = scoresByCase(earlier);
        Map<String, Double> laterScores = scoresByCase(later);
        List<PairedCaseOutcomes> pairs = new ArrayList<>();
        for (Map.Entry<String, Double> entry : earlierScores.entrySet()) {
            Double laterScore = laterScores.get(entry.getKey());
            if (laterScore != null) {
                pairs.add(PairedCaseOutcomes.of(entry.getKey(), entry.getValue(), laterScore));
            }
        }
        return pairs;
    }

    /** Case key to judge score for one run; SCORED results only. */
    Map<String, Double> scoresByCase(EvalRun run) {
        Map<String, Double> scores = new LinkedHashMap<>();
        for (EvalRunCaseResult result
            : caseResultRepository.findByRunIdOrderByPositionAscCreatedAtAsc(run.getId())) {
            if (result.getStatus() == EvalCaseResultStatus.SCORED && result.getOverallScore() != null) {
                scores.put(ReliabilitySupport.caseKey(result), result.getOverallScore());
            }
        }
        return scores;
    }
}
