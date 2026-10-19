package com.agenticai.core.quality.stats;

/**
 * The metrics the reliability surface reports, one enum constant per row of the
 * console's metric table.
 *
 * <p>An enum rather than free-text metric names because the console keys its
 * layout, ordering and tooltips off these identifiers, and a typo in a string
 * would silently produce an unrendered row. Constant names are part of the API
 * contract and are serialised verbatim.
 *
 * <p>Every constant carries the {@linkplain #label() short label} the console
 * should print and the {@linkplain #question() question it answers}, because the
 * entire point of this feature is that these four numbers are NOT
 * interchangeable and a UI that shows them side by side without saying which
 * question each answers has recreated the confusion.
 */
public enum ReliabilityMetricId {

    /**
     * Pooled per-rollout pass rate with a Wilson interval. The old headline
     * score, now with its uncertainty attached.
     */
    PASS_AT_1("pass@1", "What fraction of individual attempts pass?"),

    /**
     * Unbiased pass@k. Goes UP with k. The hiring question - see
     * {@link BinomialReliability}.
     */
    PASS_AT_K("pass@k", "If it may try k times, will it ever pass?"),

    /**
     * Unbiased pass^k. Goes DOWN with k. The operations question, and the
     * number a customer running this unattended actually buys.
     */
    PASS_HAT_K("pass^k", "Will it pass all k times running, unattended?"),

    /**
     * Mean judge score across the suite, with a hierarchical bootstrap
     * interval. Kept alongside the binomial metrics because thresholding a
     * continuous score throws away real movement - an agent drifting from 0.71
     * to 0.69 across a 0.70 bar reads as catastrophe through pass@1 and as
     * nothing at all through this.
     */
    MEAN_SCORE("mean score", "How good are the responses on the judge's own scale?");

    private final String label;
    private final String question;

    ReliabilityMetricId(String label, String question) {
        this.label = label;
        this.question = question;
    }

    /** Short label for a column header or chip, e.g. {@code "pass^k"}. */
    public String label() {
        return label;
    }

    /** The plain-English question this metric answers, for a tooltip. */
    public String question() {
        return question;
    }
}
