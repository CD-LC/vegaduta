package com.agenticai.core.quality.stats;

/**
 * Which estimator produced a {@link ConfidenceInterval}.
 *
 * <p>This is carried all the way out to the REST surface on purpose. An
 * interval without its method is not reproducible and not criticisable: a
 * Wilson interval on 8/10 and a percentile bootstrap on 10 scores can print
 * the same two numbers while meaning different things, and the only honest
 * way to let a reviewer (or a support engineer reading a screenshot six
 * months later) tell them apart is to say which one ran. The same reason
 * {@code EvalRun#judgeModel} is stored rather than inferred - provenance
 * beside the number, never behind it.
 */
public enum IntervalMethod {

    /**
     * Wilson score interval for a binomial proportion. Correct choice for
     * pass rates at the sample sizes eval suites actually have - see
     * {@link WilsonInterval} for why the normal (Wald) approximation is
     * wrong here.
     */
    WILSON_SCORE,

    /**
     * Percentile bootstrap over a flat sample of measurements. Used for
     * non-proportion metrics (mean judge score, latency) where no closed
     * form applies and the sample is not nested.
     */
    PERCENTILE_BOOTSTRAP,

    /**
     * Two-level (hierarchical / cluster) bootstrap: resample CASES with
     * replacement, then resample rollouts within each drawn case. Used for
     * suite aggregates, because an agent eval suite is nested by
     * construction and a flat bootstrap over all rollouts understates the
     * interval by pretending rollouts of the same case are independent
     * observations of the suite.
     */
    HIERARCHICAL_BOOTSTRAP,

    /**
     * Paired bootstrap over case PAIRS: each resample draws whole
     * (baseline, variant) pairs so the between-case variance cancels out of
     * the delta. This is the interval that belongs next to an A/B claim -
     * see {@link PairedComparison} for why pairing is so much more powerful
     * here than comparing two independent intervals.
     */
    PAIRED_BOOTSTRAP
}
