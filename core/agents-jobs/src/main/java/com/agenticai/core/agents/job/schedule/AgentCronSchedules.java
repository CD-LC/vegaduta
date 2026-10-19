package com.agenticai.core.agents.job.schedule;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.springframework.scheduling.support.CronExpression;

/**
 * Cron parsing and next-occurrence arithmetic for {@link AgentSchedule}.
 *
 * <p><b>Knowingly duplicates {@code WorkflowService#normalizeCron}, and that is
 * the lesser evil.</b> {@code core/agents} cannot depend on {@code
 * core/workflow} - the dependency runs the other way round - so sharing the
 * helper would mean hoisting it into a lower module, which means editing {@code
 * WorkflowService}, a large hot file, while a parallel session is live in this
 * repo. Twenty lines of duplication with a cross-reference in both javadocs is
 * cheaper and safer than that. If the two ever need to diverge, they can; if a
 * bug is found in one, this comment is how the other gets found.
 *
 * <p>The matching back-pointer on {@code WorkflowService#normalizeCron} is NOT
 * present: {@code core/workflow} is outside this PR's ownership boundary and a
 * parallel session is live in this repo, so a one-line javadoc edit there is not
 * worth a clobbered file. Whoever next opens that method should add it.
 *
 * <p>Unlike the workflow side, everything here is TIMEZONE-AWARE. {@code
 * WorkflowService#computeNextRun} evaluates the cron at {@code ZoneOffset.UTC},
 * so a workflow's "9am daily" is 9am UTC - 2:30pm in India. An agent schedule
 * carries its own {@code timezone} column and is evaluated in it, which also
 * means DST transitions are handled by {@link CronExpression} (which skips a
 * spring-forward gap to the next real instant rather than producing a time that
 * did not exist) instead of by arithmetic here.
 */
public final class AgentCronSchedules {

    /** Spring's {@link CronExpression} is a 6-field format: seconds first. */
    private static final int SPRING_CRON_FIELDS = 6;
    private static final int UNIX_CRON_FIELDS = 5;

    private AgentCronSchedules() {
    }

    /**
     * Accepts either a plain 5-field unix cron (minute hour day-of-month month
     * day-of-week - what every cron reference and every UI frequency picker
     * produces) or Spring's native 6-field form, and always returns the 6-field
     * form {@link CronExpression#parse} requires.
     *
     * <p>Anything else - four fields, garbage, six fields but malformed - is
     * returned UNCHANGED so that {@link CronExpression#parse}'s own error
     * message, rather than a guess made here, is what the tenant sees.
     *
     * <p>The normalized form is what gets PERSISTED, so every later re-parse
     * (the tick, the next-run computation, an admin reading the row) sees an
     * already-valid string and this fix never has to be repeated at those call
     * sites. That is the specific bug the workflow side hit: the fix was applied
     * on write but the stored value was the raw 5-field string, so every reader
     * had to re-apply it.
     */
    public static String normalize(String cron) {
        if (cron == null) {
            return null;
        }
        String trimmed = cron.trim();
        String[] fields = trimmed.split("\\s+");
        return fields.length == UNIX_CRON_FIELDS ? "0 " + trimmed : trimmed;
    }

    /**
     * The next instant this cron fires strictly AFTER {@code from}, evaluated in
     * {@code timezone}.
     *
     * @param normalizedCron already through {@link #normalize} - this method does
     *     not re-normalize, so that a row which somehow holds a 5-field string
     *     fails loudly here instead of being silently repaired forever.
     * @return {@code null} when the expression can never fire again (a fixed
     *     date in the past, e.g. {@code 0 0 0 29 2 *} in a non-leap century) -
     *     callers must treat that as "disable", not as "fire now".
     * @throws IllegalArgumentException if the cron cannot be parsed or the zone
     *     is unknown. Callers on the WRITE path turn this into a 400; the tick
     *     turns it into a disabled schedule plus an ERROR log, because a
     *     hand-edited row must not make the tick loop on the same failure every
     *     minute forever.
     */
    public static Instant nextAfter(String normalizedCron, String timezone, Instant from) {
        ZonedDateTime next = CronExpression.parse(normalizedCron).next(from.atZone(zoneOf(timezone)));
        return next == null ? null : next.toInstant();
    }

    /**
     * {@code null}/blank means UTC - matching the column default, so an
     * unspecified zone behaves identically whether it was defaulted by the API
     * or by the database.
     *
     * @throws IllegalArgumentException (not {@code DateTimeException}) so that
     *     both a bad cron and a bad zone surface as the same 400 through {@code
     *     GlobalExceptionHandler}'s existing {@code IllegalArgumentException}
     *     mapping, with no new handler entry needed.
     */
    public static ZoneId zoneOf(String timezone) {
        if (timezone == null || timezone.isBlank()) {
            return ZoneId.of("UTC");
        }
        try {
            return ZoneId.of(timezone.trim());
        } catch (RuntimeException unknownZone) {
            throw new IllegalArgumentException(
                "Unknown timezone '" + timezone + "'. Use an IANA zone id such as 'Asia/Kolkata' or 'UTC'.");
        }
    }

    /**
     * Whether the expression looks like the 6-field form {@link CronExpression}
     * will accept at all. Used only to produce a better error message than
     * Spring's for the common "four fields" typo; parsing remains the real check.
     */
    public static boolean hasSpringFieldCount(String cron) {
        return cron != null && cron.trim().split("\\s+").length == SPRING_CRON_FIELDS;
    }
}
