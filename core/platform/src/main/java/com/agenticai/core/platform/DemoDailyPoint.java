package com.agenticai.core.platform;

import java.time.LocalDate;

/**
 * One UTC day of the created-vs-converted series that backs the console's
 * inline chart. Days with no activity are present with zeros (the series is
 * generated from a date spine, not from the rows) so the chart's x-axis is
 * evenly spaced instead of collapsing empty days.
 *
 * <p>Unlike {@link DemoMetricsWindow}, {@code converted} here is bucketed by
 * {@code converted_at} - the day the registration actually happened - because a
 * time series is asking "what happened on this day", not "how did this cohort
 * do". The two therefore will not sum to the same thing across a window, and
 * that is correct, not a bug.
 */
public record DemoDailyPoint(
    LocalDate day,
    long created,
    long converted
) {
}
