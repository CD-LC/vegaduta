package com.agenticai.core.quality.eval;

import com.agenticai.core.quality.stats.BinomialReliability;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Repeated-rollout support for the CI gate ({@code EVAL_REPEATS}, workflow input {@code repeats}).
 *
 * <p>With {@code repeats == 1} (the default, today's behaviour) none of this is used: the runner takes
 * one sample per case and the report and exit code are byte-identical to before. With {@code repeats > 1}
 * each case is generated and judged {@code repeats} times and:
 * <ul>
 *   <li>a case PASSES the gate on a strict majority of passing rollouts - the same rule the in-app eval
 *       run uses for its own {@code repeats} - so one unlucky judge sample no longer fails a PR;</li>
 *   <li>the report adds the reliability numbers per case and in aggregate: pass@k (did it EVER get it
 *       right in k tries - the hiring question) and pass^k (did it get EVERY try right - the operations
 *       question), computed by the shared {@link BinomialReliability} (never re-derived here);</li>
 *   <li>optionally {@code EVAL_MIN_PASS_HAT_K} fails the run when the mean pass^k falls below a bar.
 *       Unset means informational only.</li>
 * </ul>
 * A rollout counts as OK when it passed AND did not blow its token budget, the same predicate
 * {@code EvalRunner}'s exit code uses for a single sample.
 */
final class EvalRepeats {

    static final String REPEATS_ENV_VAR = "EVAL_REPEATS";
    static final String MIN_PASS_HAT_K_ENV_VAR = "EVAL_MIN_PASS_HAT_K";
    /** Upper bound: every rollout is two billed model calls, and the job has a 10 minute timeout. */
    static final int MAX_REPEATS = 10;

    private EvalRepeats() {
    }

    /** One case after {@code n} rollouts. {@code representative} is what the per-case report prints. */
    record CaseSummary(EvalResult representative, int rollouts, int successes, double passAtK, double passHatK,
                       boolean casePassed) {
    }

    /** The mean per-case reliability across the run; k is the rollout count per case. */
    record Aggregate(int k, int cases, double meanPassAtK, double meanPassHatK) {
    }

    /** Parses EVAL_REPEATS: unset / blank / malformed / below 1 is 1; capped at {@link #MAX_REPEATS}. */
    static int parseRepeats(String raw) {
        if (raw == null || raw.isBlank()) {
            return 1;
        }
        try {
            int n = Integer.parseInt(raw.trim());
            return Math.max(1, Math.min(MAX_REPEATS, n));
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    /** Parses EVAL_MIN_PASS_HAT_K in [0,1]; null (no bar) when unset, blank, malformed or out of range. */
    static Double parseMinPassHatK(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            double v = Double.parseDouble(raw.trim());
            return v >= 0.0 && v <= 1.0 ? v : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static boolean rolloutOk(EvalResult r) {
        return r.passed() && !r.tokenBudgetExceeded();
    }

    /** Collapses one case's rollouts. {@code rollouts} must be non-empty. */
    static CaseSummary summarize(List<EvalResult> rollouts) {
        if (rollouts == null || rollouts.isEmpty()) {
            throw new IllegalArgumentException("A case needs at least one rollout to be summarised.");
        }
        int n = rollouts.size();
        int ok = (int) rollouts.stream().filter(EvalRepeats::rolloutOk).count();
        boolean majority = ok * 2 > n;
        // The rollout the report prints: a passing one when the case passed, otherwise a failing one so
        // the failure reason shown is a real failure reason.
        EvalResult representative = rollouts.stream()
            .filter(r -> rolloutOk(r) == majority)
            .findFirst()
            .orElse(rollouts.get(0));
        return new CaseSummary(representative, n, ok,
            BinomialReliability.passAtK(n, ok, n),
            BinomialReliability.passHatK(n, ok, n),
            majority);
    }

    static Aggregate aggregate(List<CaseSummary> summaries) {
        if (summaries.isEmpty()) {
            return new Aggregate(1, 0, 0.0, 0.0);
        }
        int k = summaries.get(0).rollouts();
        double at = summaries.stream().mapToDouble(CaseSummary::passAtK).average().orElse(0.0);
        double hat = summaries.stream().mapToDouble(CaseSummary::passHatK).average().orElse(0.0);
        return new Aggregate(k, summaries.size(), at, hat);
    }

    /**
     * The gate decision for a repeated run: any case failing its majority, or a mean pass^k under the
     * optional bar. Token-budget failures are already folded into {@link #rolloutOk}.
     */
    static boolean gateFailed(List<CaseSummary> summaries, Double minPassHatK) {
        if (summaries.stream().anyMatch(s -> !s.casePassed())) {
            return true;
        }
        return minPassHatK != null && aggregate(summaries).meanPassHatK() < minPassHatK;
    }

    /** The extra report block printed only when repeats > 1. */
    static String formatReport(List<CaseSummary> summaries, Double minPassHatK) {
        StringBuilder sb = new StringBuilder();
        Aggregate agg = aggregate(summaries);
        sb.append('\n').append("--- Reliability over ").append(agg.k()).append(" rollouts per case ---").append('\n');
        for (CaseSummary s : summaries) {
            sb.append(String.format(Locale.ROOT, "%s: %d/%d ok, pass@%d=%.3f, pass^%d=%.3f%s",
                s.representative().evalCase().id(), s.successes(), s.rollouts(), s.rollouts(), s.passAtK(),
                s.rollouts(), s.passHatK(), s.casePassed() ? "" : "  <- case FAILED (no strict majority)"))
                .append('\n');
        }
        sb.append(String.format(Locale.ROOT, "mean pass@%d=%.3f (could it ever do it), mean pass^%d=%.3f "
                + "(does it do it every time)", agg.k(), agg.meanPassAtK(), agg.k(), agg.meanPassHatK()))
            .append('\n');
        if (minPassHatK != null) {
            sb.append(String.format(Locale.ROOT, "%s=%.3f -> %s", MIN_PASS_HAT_K_ENV_VAR, minPassHatK,
                agg.meanPassHatK() >= minPassHatK ? "met" : "NOT MET")).append('\n');
        }
        return sb.toString();
    }

    /** The per-case representatives, in dataset order, for the standard per-case report. */
    static List<EvalResult> representatives(List<CaseSummary> summaries) {
        List<EvalResult> out = new ArrayList<>(summaries.size());
        for (CaseSummary s : summaries) {
            out.add(s.representative());
        }
        return out;
    }
}
