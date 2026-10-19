package com.agenticai.core.tutor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.tutor.SpacedRepetition.Schedule;
import com.agenticai.core.tutor.SpacedRepetition.State;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The scheduler and the grader are pure: same input, same output, no clock and no model. */
class SpacedRepetitionTest {

    private static final Instant NOW = Instant.parse("2026-10-03T10:00:00Z");

    private static State state(Schedule s) {
        return new State(s.ease(), s.intervalDays(), s.reps(), s.lapses());
    }

    @Test
    void correctAnswersGrowTheIntervalGeometrically() {
        List<Integer> intervals = new ArrayList<>();
        State state = SpacedRepetition.fresh();
        for (int i = 0; i < 5; i++) {
            Schedule next = SpacedRepetition.next(state, true, NOW);
            intervals.add(next.intervalDays());
            assertEquals(NOW.plus(Duration.ofDays(next.intervalDays())), next.dueAt());
            state = state(next);
        }
        assertEquals(List.of(1, 3, 7, 16, 37), intervals);
        assertEquals(SpacedRepetition.DEFAULT_EASE, state.ease());
        assertEquals(5, state.reps());
        assertEquals(0, state.lapses());
    }

    @Test
    void aWrongAnswerIsALapseDueTomorrow() {
        State learnt = new State(2.3, 16, 4, 0);

        Schedule next = SpacedRepetition.next(learnt, false, NOW);

        assertEquals(1, next.intervalDays());
        assertEquals(0, next.reps());
        assertEquals(1, next.lapses());
        assertEquals(2.1, next.ease());
        assertEquals(NOW.plus(Duration.ofDays(1)), next.dueAt());
    }

    @Test
    void easeNeverDropsBelowTheFloor() {
        State state = SpacedRepetition.fresh();
        for (int i = 0; i < 20; i++) {
            state = state(SpacedRepetition.next(state, false, NOW));
        }
        assertEquals(SpacedRepetition.MIN_EASE, state.ease());
        assertEquals(20, state.lapses());
    }

    @Test
    void recoveryAfterALapseStartsAgainFromOneDay() {
        State lapsed = state(SpacedRepetition.next(new State(2.3, 16, 4, 0), false, NOW));

        Schedule first = SpacedRepetition.next(lapsed, true, NOW);
        Schedule second = SpacedRepetition.next(state(first), true, NOW);

        assertEquals(1, first.intervalDays());
        assertEquals(3, second.intervalDays());
        assertEquals(1, second.lapses());
    }

    @Test
    void theIntervalIsCappedAtAYear() {
        Schedule next = SpacedRepetition.next(new State(2.3, 300, 9, 0), true, NOW);
        assertEquals(SpacedRepetition.MAX_INTERVAL_DAYS, next.intervalDays());
    }

    @Test
    void aNullOrCorruptStateIsTreatedAsFresh() {
        assertEquals(1, SpacedRepetition.next(null, true, NOW).intervalDays());
        assertEquals(SpacedRepetition.DEFAULT_EASE, SpacedRepetition.next(new State(0, 0, 0, 0), true, NOW).ease());
    }

    @Test
    void dueMeansTheMomentHasBeenReached() {
        assertTrue(SpacedRepetition.isDue(NOW, NOW));
        assertTrue(SpacedRepetition.isDue(NOW.minusSeconds(1), NOW));
        assertTrue(SpacedRepetition.isDue(null, NOW));
        assertFalse(SpacedRepetition.isDue(NOW.plusSeconds(1), NOW));
    }

    @Test
    void theQueueHoldsOnlyDueItemsMostOverdueFirstUpToTheCap() {
        List<Instant> items = List.of(
            NOW.plusSeconds(60), NOW.minusSeconds(10), NOW.minusSeconds(500), NOW, NOW.minusSeconds(100));

        List<Instant> queue = SpacedRepetition.queue(items, i -> i, NOW, 3);

        assertEquals(List.of(NOW.minusSeconds(500), NOW.minusSeconds(100), NOW.minusSeconds(10)), queue);
        assertEquals(4, SpacedRepetition.queue(items, i -> i, NOW, 12).size());
    }

    @Test
    void gradingIsAnExactIndexMatch() {
        assertTrue(QuizGrader.isCorrect(2, 4, 2));
        assertFalse(QuizGrader.isCorrect(2, 4, 0));
    }

    @Test
    void gradingRefusesAMissingOrOutOfRangeSelection() {
        assertThrows(IllegalArgumentException.class, () -> QuizGrader.isCorrect(0, 4, null));
        assertThrows(IllegalArgumentException.class, () -> QuizGrader.isCorrect(0, 4, -1));
        assertThrows(IllegalArgumentException.class, () -> QuizGrader.isCorrect(0, 4, 4));
    }
}
