package com.agenticai.core.platform;

/**
 * One time window of the demo funnel.
 *
 * <p><b>The rate is a COHORT rate, not a same-period ratio.</b> {@code
 * converted} counts sandboxes that were CREATED inside this window and have
 * since registered - not sandboxes that happened to convert inside it. That
 * keeps numerator and denominator over the same population, which is the only
 * definition where "conversion rate" means what an operator thinks it means. A
 * consequence worth stating: the {@code today} rate is structurally pessimistic
 * (a sandbox created an hour ago has barely had a chance to convert), which is
 * why the console leads with the 30-day figure.
 *
 * @param conversionRate converted/created as a 0-1 fraction, or {@code null}
 *     when {@code created} is 0 - an undefined rate is reported as undefined
 *     rather than as a fabricated 0%.
 */
public record DemoMetricsWindow(
    String window,
    long created,
    long converted,
    Double conversionRate
) {

    static DemoMetricsWindow of(String window, long created, long converted) {
        Double rate = created > 0 ? ((double) converted) / created : null;
        return new DemoMetricsWindow(window, created, converted, rate);
    }
}
