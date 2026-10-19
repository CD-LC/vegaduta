package com.agenticai.core.brief;

import com.agenticai.core.brief.ProofData.EvalRunPoint;
import com.agenticai.core.brief.ProofData.ExperimentRow;
import com.agenticai.core.brief.ProofData.Variant;
import com.agenticai.core.brief.dto.LedgerDtos.Comparison;
import com.agenticai.core.brief.dto.LedgerDtos.RunView;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The proof ledger's arithmetic, pure so it can be tested without a platform: score deltas judged against the
 * measured noise floor, and the two-proportion test that decides whether an experiment's thumbs difference is real.
 *
 * <p>The one rule that matters: a change is "significant" only when a noise floor was actually measured and the
 * change is larger than it. With no floor there is no claim - {@code UNKNOWN_NOISE}, {@code significant=false}.
 */
final class LedgerMath {

    static final String BASELINE = "BASELINE";
    static final String SIGNIFICANT_UP = "SIGNIFICANT_UP";
    static final String SIGNIFICANT_DOWN = "SIGNIFICANT_DOWN";
    static final String WITHIN_NOISE = "WITHIN_NOISE";
    static final String UNKNOWN_NOISE = "UNKNOWN_NOISE";

    /** Each experiment arm needs this many rated turns before a thumbs difference is compared at all. */
    static final int MIN_FEEDBACK_PER_ARM = 10;

    /** Two-sided 95%. */
    private static final double Z_95 = 1.96;

    private LedgerMath() {
    }

    /** A brief version and when it was saved; runs belong to the newest version saved before they started. */
    record VersionStamp(int version, Instant at) {
    }

    /**
     * @param chronological finished, measured runs, oldest first
     * @param versions the brief's versions in any order
     * @param noiseFloor the dataset's measured floor, or null when unknown
     */
    static List<RunView> runs(List<EvalRunPoint> chronological, List<VersionStamp> versions, Double noiseFloor) {
        List<RunView> out = new ArrayList<>();
        Double previous = null;
        for (EvalRunPoint run : chronological) {
            Double delta = previous == null ? null : run.score() - previous;
            String change = classify(delta, noiseFloor);
            boolean significant = SIGNIFICANT_UP.equals(change) || SIGNIFICANT_DOWN.equals(change);
            out.add(new RunView(versionAt(versions, run.at()), run.runId(), run.at(), run.score(), run.passThreshold(),
                run.verdict(), noiseFloor, delta, significant, change));
            previous = run.score();
        }
        return out;
    }

    static String classify(Double delta, Double noiseFloor) {
        if (delta == null) {
            return BASELINE;
        }
        if (noiseFloor == null) {
            return UNKNOWN_NOISE;
        }
        if (Math.abs(delta) > noiseFloor) {
            return delta > 0 ? SIGNIFICANT_UP : SIGNIFICANT_DOWN;
        }
        return WITHIN_NOISE;
    }

    /** The version current at {@code at}: the newest saved at or before it, else the oldest version there is. */
    static int versionAt(List<VersionStamp> versions, Instant at) {
        int best = -1;
        Instant bestAt = null;
        int oldest = Integer.MAX_VALUE;
        for (VersionStamp v : versions) {
            oldest = Math.min(oldest, v.version());
            if (!v.at().isAfter(at) && (bestAt == null || v.at().isAfter(bestAt)
                || (v.at().equals(bestAt) && v.version() > best))) {
                best = v.version();
                bestAt = v.at();
            }
        }
        if (best >= 0) {
            return best;
        }
        return oldest == Integer.MAX_VALUE ? 1 : oldest;
    }

    /** Thumbs-up over rated turns; null (never 0) when nothing was rated. */
    static Double ratio(long up, long rated) {
        return rated <= 0 ? null : (double) up / rated;
    }

    /**
     * Control (the running version) against treatment (the earlier version) on thumbs-up rate, by a two-proportion z
     * test. Null when either arm has fewer than {@link #MIN_FEEDBACK_PER_ARM} rated turns - unmeasured is not a tie.
     * Never significant when the arms hold too few of either outcome for the normal approximation to be valid.
     */
    static Comparison compare(ExperimentRow row) {
        Variant c = row.control();
        Variant t = row.treatment();
        if (c == null || t == null || c.thumbsUpRatePct() == null || t.thumbsUpRatePct() == null
            || c.feedbackCount() < MIN_FEEDBACK_PER_ARM || t.feedbackCount() < MIN_FEEDBACK_PER_ARM) {
            return null;
        }
        double n1 = c.feedbackCount();
        double n2 = t.feedbackCount();
        double p1 = c.thumbsUpRatePct() / 100.0;
        double p2 = t.thumbsUpRatePct() / 100.0;
        double pooled = (p1 * n1 + p2 * n2) / (n1 + n2);
        double se = Math.sqrt(pooled * (1 - pooled) * (1 / n1 + 1 / n2));
        double delta = p1 - p2;
        // The normal approximation behind a z-test needs at least 5 expected successes and failures per arm; below that
        // the arms are too small to call a win, so it is never reported as one (the numbers are still shown).
        boolean testHolds = Math.min(n1, n2) * Math.min(pooled, 1 - pooled) >= 5;
        boolean significant = testHolds && se > 0 && Math.abs(delta / se) >= Z_95;
        String summary = String.format(Locale.ROOT,
            "Running version %d%% thumbs-up over %d rated turns against %d%% over %d for the earlier version (%+.0f points) - %s.",
            Math.round(p1 * 100), c.feedbackCount(), Math.round(p2 * 100), t.feedbackCount(), delta * 100,
            significant ? "a real difference" : "not distinguishable from chance");
        return new Comparison(significant, delta, summary);
    }
}
