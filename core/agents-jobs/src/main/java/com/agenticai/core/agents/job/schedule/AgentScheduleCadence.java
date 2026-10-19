package com.agenticai.core.agents.job.schedule;

import java.time.Duration;
import java.time.Instant;

/**
 * A schedule's cadence: EXACTLY ONE of a cron expression or a fixed interval.
 *
 * <p>Sibling of {@link AgentCronSchedules}, which keeps owning cron parsing. This
 * class adds the interval alternative and the single place that answers "when is
 * the next occurrence" for either, so {@code AgentScheduleService} never branches
 * on cron-vs-interval anywhere else.
 *
 * <p>Interval occurrences are computed from {@code from} (the tick's {@code now}),
 * not from the missed slot - the same no-backfill rule cron uses, so downtime
 * produces one firing on boot rather than a burst. The tick runs once a minute,
 * so an interval schedule is honoured to within roughly one tick.
 *
 * @param cron normalized 6-field cron, or {@code null} when this is an interval
 * @param intervalSeconds at least {@link #MIN_INTERVAL_SECONDS}, or {@code null}
 *     when this is a cron
 */
public record AgentScheduleCadence(String cron, Integer intervalSeconds) {

    public static final int MIN_INTERVAL_SECONDS = 60;
    /** One year; anything larger is almost certainly a units mistake. */
    public static final int MAX_INTERVAL_SECONDS = 365 * 24 * 3600;

    /**
     * Validates the pair and normalizes the cron.
     *
     * @throws IllegalArgumentException (mapped to 400) unless exactly one of the
     *     two is supplied and, for an interval, it is within bounds
     */
    public static AgentScheduleCadence of(String rawCron, Integer intervalSeconds) {
        boolean hasCron = rawCron != null && !rawCron.isBlank();
        boolean hasInterval = intervalSeconds != null;
        if (hasCron == hasInterval) {
            throw new IllegalArgumentException(
                "Invalid schedule: set exactly one of 'cron' or 'intervalSeconds' (got "
                    + (hasCron ? "both" : "neither") + ").");
        }
        if (hasCron) {
            return new AgentScheduleCadence(AgentCronSchedules.normalize(rawCron), null);
        }
        if (intervalSeconds < MIN_INTERVAL_SECONDS) {
            throw new IllegalArgumentException(
                "Invalid schedule: intervalSeconds must be at least " + MIN_INTERVAL_SECONDS + " (one minute).");
        }
        if (intervalSeconds > MAX_INTERVAL_SECONDS) {
            throw new IllegalArgumentException(
                "Invalid schedule: intervalSeconds must be at most " + MAX_INTERVAL_SECONDS + " (one year).");
        }
        return new AgentScheduleCadence(null, intervalSeconds);
    }

    /** Rebuilds a cadence from stored columns without re-validating bounds. */
    public static AgentScheduleCadence stored(String cron, Integer intervalSeconds) {
        return new AgentScheduleCadence(cron, intervalSeconds);
    }

    public boolean isInterval() {
        return intervalSeconds != null;
    }

    /**
     * The next occurrence strictly after {@code from}; {@code null} when a cron can
     * never fire again. An interval always has a next occurrence.
     *
     * @throws IllegalArgumentException for an unparseable cron or unknown zone
     */
    public Instant nextAfter(String timezone, Instant from) {
        if (isInterval()) {
            AgentCronSchedules.zoneOf(timezone);
            return from.plusSeconds(intervalSeconds);
        }
        return AgentCronSchedules.nextAfter(cron, timezone, from);
    }

    /** The gap between the schedule's own first and second firing, or null if there is no second. */
    public Duration gapAfter(String timezone, Instant firstRun) {
        Instant second = nextAfter(timezone, firstRun);
        return second == null ? null : Duration.between(firstRun, second);
    }

    /** Short label for logs and {@code agent_job.origin_ref}. */
    public String describe() {
        return isInterval() ? "every " + intervalSeconds + "s" : cron;
    }
}
