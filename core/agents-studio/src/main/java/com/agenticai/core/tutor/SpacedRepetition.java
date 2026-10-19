package com.agenticai.core.tutor;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;

/**
 * The tutor's review scheduler: SM-2-lite, pure and deterministic - no model call, no clock of its
 * own, no I/O. Ported from the Academy's {@code review.ts} with one simplification.
 *
 * <p>A multiple-choice card gives a binary signal, so exactly two outcomes exist:
 * <ul>
 *   <li><b>correct</b>: ease is unchanged and the interval grows - 1 day, 3 days, then
 *       {@code round(previous * ease)} (1, 3, 7, 16, 37 ... at the default ease), capped at a year.</li>
 *   <li><b>incorrect</b>: a lapse - ease drops by {@link #LAPSE_EASE_PENALTY} (floored at
 *       {@link #MIN_EASE}), repetitions reset to 0 and the card comes back tomorrow.</li>
 * </ul>
 */
public final class SpacedRepetition {

    public static final double DEFAULT_EASE = 2.3;
    public static final double MIN_EASE = 1.3;
    public static final double LAPSE_EASE_PENALTY = 0.2;
    public static final int MAX_INTERVAL_DAYS = 365;
    /** Cards handed out per review sitting - short, consistent sessions are the point. */
    public static final int SESSION_CARD_CAP = 12;

    private SpacedRepetition() {
    }

    /** Scheduling state of one card before an answer. */
    public record State(double ease, int intervalDays, int reps, int lapses) {
    }

    /** Scheduling state after an answer, with the moment the card next becomes due. */
    public record Schedule(double ease, int intervalDays, int reps, int lapses, Instant dueAt) {
    }

    /** A card nobody has reviewed yet. */
    public static State fresh() {
        return new State(DEFAULT_EASE, 0, 0, 0);
    }

    public static Schedule next(State previous, boolean correct, Instant now) {
        State prev = previous == null ? fresh() : previous;
        double prevEase = prev.ease() < MIN_EASE ? DEFAULT_EASE : prev.ease();
        if (!correct) {
            double ease = Math.max(MIN_EASE, roundEase(prevEase - LAPSE_EASE_PENALTY));
            return new Schedule(ease, 1, 0, Math.max(0, prev.lapses()) + 1, now.plus(Duration.ofDays(1)));
        }
        int reps = Math.max(0, prev.reps()) + 1;
        int interval;
        if (reps == 1) {
            interval = 1;
        } else if (reps == 2) {
            interval = 3;
        } else {
            int prevInterval = Math.max(1, prev.intervalDays());
            interval = Math.max(prevInterval + 1, (int) Math.round(prevInterval * prevEase));
        }
        interval = Math.min(MAX_INTERVAL_DAYS, interval);
        return new Schedule(roundEase(prevEase), interval, reps, Math.max(0, prev.lapses()), now.plus(Duration.ofDays(interval)));
    }

    /** A card is due once its due moment has been reached; a card with no due moment is due now. */
    public static boolean isDue(Instant dueAt, Instant now) {
        return dueAt == null || !dueAt.isAfter(now);
    }

    /**
     * The review queue: only due items, most overdue first, at most {@code cap}. Stable for equal due
     * moments (insertion order), so the same deck always yields the same sitting.
     */
    public static <T> List<T> queue(List<T> items, Function<T, Instant> dueAt, Instant now, int cap) {
        List<T> due = new ArrayList<>();
        for (T item : items) {
            if (isDue(dueAt.apply(item), now)) {
                due.add(item);
            }
        }
        due.sort(Comparator.comparing(item -> {
            Instant at = dueAt.apply(item);
            return at == null ? Instant.EPOCH : at;
        }));
        return due.size() > cap ? new ArrayList<>(due.subList(0, Math.max(0, cap))) : due;
    }

    private static double roundEase(double ease) {
        return Math.round(ease * 10_000d) / 10_000d;
    }
}
