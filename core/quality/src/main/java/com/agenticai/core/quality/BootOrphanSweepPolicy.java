package com.agenticai.core.quality;

import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.Locale;

/**
 * Parses {@code app.quality.boot-orphan-sweep} / {@code app.quality.boot-orphan-min-age}, shared by
 * {@code EvalRunExecutor}, {@code SimulationRunExecutor} and {@link QualityRunOrphanSweep}.
 *
 * <p>Eval and simulation runs execute on an in-process executor with no owner column, so "a QUEUED or
 * RUNNING row" cannot tell a dead JVM's run from a run another replica (or the previous revision during
 * a rolling deploy) is still executing:
 * <ul>
 *   <li>{@link Mode#ALL} (default, today's behaviour): every live row is failed at boot.</li>
 *   <li>{@link Mode#STALE}: only rows older than the minimum age are failed, at boot and then
 *       periodically under the Postgres advisory lock.</li>
 *   <li>{@link Mode#OFF}: nothing is failed at boot; the periodic stale sweep still runs.</li>
 * </ul>
 * Anything unrecognised parses as {@link Mode#ALL} (fail safe = old behaviour).
 */
public final class BootOrphanSweepPolicy {

    public enum Mode { ALL, STALE, OFF }

    /** Used when the configured minimum age is blank, unparsable or not positive. */
    public static final Duration DEFAULT_MIN_AGE = Duration.ofHours(2);

    public static final String STALE_REASON =
        "Orphaned: this run was still QUEUED or RUNNING long after it started and its executing replica is "
            + "presumed gone (eval and simulation runs execute in-process and cannot resume). Trigger the run again.";

    private BootOrphanSweepPolicy() {
    }

    public static Mode mode(String raw) {
        if (raw == null) {
            return Mode.ALL;
        }
        return switch (raw.strip().toLowerCase(Locale.ROOT)) {
            case "stale" -> Mode.STALE;
            case "off" -> Mode.OFF;
            default -> Mode.ALL;
        };
    }

    public static Duration minAge(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT_MIN_AGE;
        }
        try {
            Duration parsed = Duration.parse(raw.strip());
            return parsed.isZero() || parsed.isNegative() ? DEFAULT_MIN_AGE : parsed;
        } catch (DateTimeParseException e) {
            return DEFAULT_MIN_AGE;
        }
    }
}
