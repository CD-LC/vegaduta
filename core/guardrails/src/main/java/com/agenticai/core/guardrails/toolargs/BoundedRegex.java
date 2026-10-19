package com.agenticai.core.guardrails.toolargs;

import java.util.regex.Pattern;

/**
 * Runs an author-supplied regex against a model-supplied value under a hard step budget.
 *
 * <p>Both sides are untrusted: the pattern comes from a workspace editor and the value from a model that
 * may be under a prompt injection's control, so a pattern that is fine on honest input can still be driven
 * into catastrophic backtracking. The match runs on the CALLING thread (this is on the tool-dispatch hot
 * path, so no pool and no timeout thread) over a {@link CharSequence} that counts every {@code charAt} and
 * throws once {@link #STEP_BUDGET} is spent. {@code java.util.regex} reads its input only through
 * {@code charAt}, so the count bounds the work. Callers also cap the value length before getting here
 * ({@link ToolArgPolicyEvaluator#MAX_PATTERN_INPUT_CHARS}).
 *
 * <p>Budget exhaustion is reported as {@link BudgetExceeded}; the evaluator turns it into a violation, so a
 * value the policy cannot verify is refused rather than waved through.
 */
final class BoundedRegex {

    /** charAt reads allowed for one match: tens of milliseconds at the very worst, far above honest use. */
    static final long STEP_BUDGET = 2_000_000L;

    private BoundedRegex() {
    }

    /** Thrown when a match used up its budget (or recursed too deeply). Carries no stack trace. */
    static final class BudgetExceeded extends RuntimeException {
        BudgetExceeded() {
            super(null, null, false, false);
        }
    }

    /**
     * Whether the WHOLE value matches the pattern.
     *
     * @throws BudgetExceeded when the pattern is too expensive on this value
     */
    static boolean matchesWhole(Pattern pattern, CharSequence value) {
        return matchesWhole(pattern, value, STEP_BUDGET);
    }

    static boolean matchesWhole(Pattern pattern, CharSequence value, long stepBudget) {
        try {
            return pattern.matcher(new Counting(value, stepBudget)).matches();
        } catch (StackOverflowError e) {
            throw new BudgetExceeded();
        }
    }

    private static final class Counting implements CharSequence {
        private final CharSequence inner;
        private long left;

        Counting(CharSequence inner, long budget) {
            this.inner = inner;
            this.left = budget;
        }

        @Override
        public int length() {
            return inner.length();
        }

        @Override
        public char charAt(int index) {
            if (--left < 0) {
                throw new BudgetExceeded();
            }
            return inner.charAt(index);
        }

        @Override
        public CharSequence subSequence(int start, int end) {
            Counting sub = new Counting(inner.subSequence(start, end), 0);
            sub.left = this.left;
            return sub;
        }

        @Override
        public String toString() {
            return inner.toString();
        }
    }
}
