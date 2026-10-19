package com.agenticai.core.workflow;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Optional;
import org.springframework.scheduling.support.CronExpression;

/**
 * The one place a schedule's cron text is normalised, shared by {@link WorkflowService}'s schedule
 * endpoints and the AI generator's schedule suggestion so the two can never disagree about what a
 * valid schedule is.
 */
final class WorkflowCronSupport {

    private WorkflowCronSupport() {
    }

    /**
     * Accepts either a plain 5-field unix cron (minute hour dom month dow - what the canvas sends)
     * or Spring's native 6-field form (seconds first), and always returns the 6-field form
     * {@link CronExpression#parse} requires. Anything else is passed through unchanged so
     * {@link CronExpression#parse}'s own error message - not a guess here - is what the caller sees.
     */
    static String normalize(String cron) {
        if (cron == null) {
            return null;
        }
        String trimmed = cron.trim();
        String[] fields = trimmed.split("\\s+");
        return fields.length == 5 ? "0 " + trimmed : trimmed;
    }

    /**
     * The 5-field form of {@code cron} when it is a schedule the platform would accept, else empty.
     * A 6-field input is accepted only when its seconds field is {@code 0} (so dropping it loses
     * nothing); whitespace runs are collapsed.
     */
    static Optional<String> validFiveField(String cron) {
        if (cron == null || cron.isBlank()) {
            return Optional.empty();
        }
        String[] fields = cron.trim().split("\\s+");
        String five;
        if (fields.length == 5) {
            five = String.join(" ", fields);
        } else if (fields.length == 6 && "0".equals(fields[0])) {
            five = String.join(" ", Arrays.copyOfRange(fields, 1, 6));
        } else {
            return Optional.empty();
        }
        try {
            CronExpression.parse(normalize(five));
            return Optional.of(five);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /** Next fire strictly after {@code from} (UTC), or null when the expression never fires again. */
    static Instant nextRun(CronExpression cron, Instant from) {
        LocalDateTime next = cron.next(LocalDateTime.ofInstant(from, ZoneOffset.UTC));
        return next == null ? null : next.toInstant(ZoneOffset.UTC);
    }
}
