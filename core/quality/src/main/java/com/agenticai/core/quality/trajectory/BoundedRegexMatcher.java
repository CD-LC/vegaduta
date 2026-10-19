package com.agenticai.core.quality.trajectory;

import java.util.regex.Pattern;

/**
 * Runs an author-supplied regex over a model-produced value under a hard charAt budget, on the calling thread
 * (the eval worker). {@code java.util.regex} reads its input only through {@code charAt}, so counting those
 * reads bounds the work; once the budget is spent the match is abandoned and reported as "no match". Callers
 * also cap the value length first. (A small duplicate of the idea in {@code CodeGrader}'s bounded matcher,
 * kept separate because that one is package-private and runs on a thread pool this path does not need.)
 */
final class BoundedRegexMatcher {

    /** charAt reads allowed for one match; far above honest use, a few milliseconds of work at the very worst. */
    static final long STEP_BUDGET = 2_000_000L;

    private BoundedRegexMatcher() {
    }

    private static final class BudgetExceeded extends RuntimeException {
        BudgetExceeded() {
            super(null, null, false, false);
        }
    }

    /** True when the pattern is found in the text; false on no match OR when the budget ran out. */
    static boolean find(Pattern pattern, String text) {
        return find(pattern, text, STEP_BUDGET);
    }

    static boolean find(Pattern pattern, String text, long budget) {
        try {
            return pattern.matcher(new Counting(text, budget)).find();
        } catch (BudgetExceeded | StackOverflowError e) {
            return false;
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
