package com.agenticai.core.quality.calibration;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Pure statistics for "can this LLM judge be trusted?": agreement with human
 * labels (Cohen's kappa) and position-bias (order-swap consistency) over a
 * labelled set of pairwise comparisons. No I/O - the judge is a function.
 *
 * <p>Each example is two candidate answers to one prompt plus the HUMAN's
 * preferred one ({@code "A"} or {@code "B"}). The judge is asked twice, with
 * the answers in both orders:
 * <ul>
 *   <li><b>order-swap consistency</b> = fraction of examples where the judge
 *       picked the same underlying answer in both orders. A judge that
 *       simply prefers whichever answer comes first scores ~0.5 here; this is
 *       the standard position-bias check.</li>
 *   <li><b>Cohen's kappa</b> of the judge's (consistent) pick against the
 *       human label, computed only over the consistent examples - an
 *       order-flipping judgment is not a judgment. {@code coverage} says how
 *       many examples that left, so a high kappa on 3 of 30 is not mistaken
 *       for trust.</li>
 * </ul>
 * Kappa is {@code null} (not 0, not 1) when it is undefined: no consistent
 * judgments, or both raters used a single label throughout (chance agreement
 * of 1).
 */
public final class JudgeCalibration {

    /** Below this many labelled examples the report carries a "too small to trust" warning. */
    public static final int RECOMMENDED_MIN_EXAMPLES = 30;

    private JudgeCalibration() {
    }

    /** One labelled comparison. {@code humanPreferred} is "A" or "B" (case-insensitive). */
    public record LabeledPair(String id, String prompt, String answerA, String answerB, String humanPreferred) {
    }

    /**
     * The judge under test. Receives the prompt and two answers in the order
     * to show them; returns 1 if it prefers the FIRST shown, 2 for the
     * SECOND, anything else (null/0/other) when it gave no usable verdict.
     */
    @FunctionalInterface
    public interface PairwiseJudge {
        Integer prefer(String prompt, String first, String second);
    }

    /** Per-example outcome, kept so a reviewer can see exactly where the judge flipped. */
    public record ExampleOutcome(
        String id,
        String human,
        String judgeAB,      // pick when shown in original order: "A"/"B"/null
        String judgeBA,      // pick when shown swapped, mapped back to the original labels
        boolean consistent   // both picks present and identical
    ) {
    }

    public record Report(
        int examples,
        int usable,                 // examples where both orderings returned a verdict
        int consistent,             // usable examples where the two orderings agreed
        Double orderSwapConsistency, // consistent / usable; null when usable == 0
        int coverage,               // == consistent: examples that entered kappa
        Double rawAgreement,        // agreement with human over consistent examples; null when none
        Double cohenKappa,          // null when undefined
        String interpretation,
        List<String> warnings,
        List<ExampleOutcome> outcomes
    ) {
    }

    public static Report evaluate(List<LabeledPair> pairs, PairwiseJudge judge) {
        List<ExampleOutcome> outcomes = new ArrayList<>(pairs.size());
        int usable = 0;
        int consistent = 0;
        int agree = 0;
        // 2x2 counts over consistent examples: [human][judge], index 0 = A, 1 = B.
        int[][] table = new int[2][2];

        for (LabeledPair p : pairs) {
            String human = normalize(p.humanPreferred());
            Integer first = safe(judge, p.prompt(), p.answerA(), p.answerB());
            Integer second = safe(judge, p.prompt(), p.answerB(), p.answerA());
            String ab = first == null ? null : (first == 1 ? "A" : "B");
            // In the swapped call "first shown" is answer B, so 1 -> B, 2 -> A.
            String ba = second == null ? null : (second == 1 ? "B" : "A");
            boolean both = ab != null && ba != null;
            boolean same = both && ab.equals(ba);
            if (both) {
                usable++;
            }
            if (same) {
                consistent++;
                int h = "A".equals(human) ? 0 : 1;
                int j = "A".equals(ab) ? 0 : 1;
                table[h][j]++;
                if (h == j) {
                    agree++;
                }
            }
            outcomes.add(new ExampleOutcome(p.id(), human, ab, ba, same));
        }

        Double swap = usable == 0 ? null : (double) consistent / usable;
        Double raw = consistent == 0 ? null : (double) agree / consistent;
        Double kappa = kappa(table, consistent);

        List<String> warnings = new ArrayList<>();
        if (pairs.size() < RECOMMENDED_MIN_EXAMPLES) {
            warnings.add("Only " + pairs.size() + " labelled examples; at least "
                + RECOMMENDED_MIN_EXAMPLES + " are recommended before trusting a kappa estimate.");
        }
        if (usable < pairs.size()) {
            warnings.add((pairs.size() - usable) + " example(s) got no usable verdict from the judge in one "
                + "or both orderings and are excluded.");
        }
        if (swap != null && swap < 0.8) {
            warnings.add("Order-swap consistency is " + String.format(Locale.ROOT, "%.2f", swap)
                + ": the judge's pick often depends on which answer is shown first (position bias).");
        }
        if (kappa == null && consistent > 0) {
            warnings.add("Kappa is undefined: the human labels and the judge's picks each used a single "
                + "label, so there is no agreement-beyond-chance to measure. Add examples where the "
                + "preferred answer differs.");
        }
        return new Report(pairs.size(), usable, consistent, swap, consistent, raw, kappa,
            interpret(kappa), warnings, outcomes);
    }

    /**
     * Cohen's kappa for two raters and two labels from their 2x2 table.
     * {@code null} when n == 0 or chance agreement is exactly 1.
     */
    public static Double kappa(int[][] table, int n) {
        if (n == 0) {
            return null;
        }
        double po = (double) (table[0][0] + table[1][1]) / n;
        double humanA = (double) (table[0][0] + table[0][1]) / n;
        double humanB = 1.0 - humanA;
        double judgeA = (double) (table[0][0] + table[1][0]) / n;
        double judgeB = 1.0 - judgeA;
        double pe = humanA * judgeA + humanB * judgeB;
        if (Math.abs(1.0 - pe) < 1e-12) {
            return null;
        }
        return (po - pe) / (1.0 - pe);
    }

    /**
     * Self-preference warning: judges rate their own model family's output
     * higher. Returns null when the judge and the agent under test are
     * different models (or either is unknown); compares the model part of a
     * {@code PROVIDER/model} id, case-insensitively.
     */
    public static String selfPreferenceWarning(String judgeModelId, String agentModel) {
        if (judgeModelId == null || agentModel == null || judgeModelId.isBlank() || agentModel.isBlank()) {
            return null;
        }
        String judge = modelPart(judgeModelId);
        String agent = modelPart(agentModel);
        if (judge.equalsIgnoreCase(agent)) {
            return "The judge model ('" + judgeModelId + "') is the same model as the agent being evaluated ('"
                + agentModel + "'). LLM judges favour their own outputs (self-preference bias), so scores from "
                + "this judge on this agent are inflated. Use a different judge model.";
        }
        return null;
    }

    static String interpret(Double kappa) {
        if (kappa == null) {
            return "undefined";
        }
        // Landis & Koch (1977) bands.
        if (kappa < 0.0) {
            return "worse than chance";
        }
        if (kappa <= 0.20) {
            return "slight agreement";
        }
        if (kappa <= 0.40) {
            return "fair agreement";
        }
        if (kappa <= 0.60) {
            return "moderate agreement";
        }
        if (kappa <= 0.80) {
            return "substantial agreement";
        }
        return "almost perfect agreement";
    }

    private static Integer safe(PairwiseJudge judge, String prompt, String first, String second) {
        try {
            Integer v = judge.prefer(prompt, first, second);
            return v != null && (v == 1 || v == 2) ? v : null;
        } catch (com.agenticai.core.quality.impl.JudgeCallGuard.JudgeRunAbortedException abort) {
            throw abort; // the run is being stopped on purpose (dead judge / budget): not "no verdict"
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String normalize(String label) {
        return label != null && label.trim().equalsIgnoreCase("B") ? "B" : "A";
    }

    private static String modelPart(String id) {
        int slash = id.lastIndexOf('/');
        return (slash >= 0 ? id.substring(slash + 1) : id).trim();
    }
}
