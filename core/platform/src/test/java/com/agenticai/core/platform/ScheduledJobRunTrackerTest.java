package com.agenticai.core.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.platform.ops.ScheduledJobRunTracker;
import com.agenticai.core.platform.ops.ScheduledJobRunTracker.RunState;
import io.micrometer.observation.Observation;
import java.lang.reflect.Method;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.support.ScheduledTaskObservationContext;

/**
 * Last-run bookkeeping fed by Spring's scheduled-task observations: a run is
 * RUNNING between onStart and onStop, a failed run keeps the exception's
 * first line (bounded), and state is per job key (declaring class + method).
 */
class ScheduledJobRunTrackerTest {

    private ScheduledJobRunTracker tracker;
    private Method method;
    private String key;

    /** A stand-in for a {@code @Scheduled} method. */
    @SuppressWarnings("unused")
    private void probe() {}

    @BeforeEach
    void setUp() throws Exception {
        tracker = new ScheduledJobRunTracker();
        method = ScheduledJobRunTrackerTest.class.getDeclaredMethod("probe");
        key = ScheduledJobRunTrackerTest.class.getName() + ".probe";
    }

    private ScheduledTaskObservationContext context() {
        return new ScheduledTaskObservationContext(method);
    }

    // ------------------------------------------------------------- supportsContext

    @Test
    void onlyScheduledTaskContextsAreSupported() {
        assertTrue(tracker.supportsContext(context()));
        assertFalse(tracker.supportsContext(new Observation.Context()));
    }

    // ------------------------------------------------------------- happy path

    @Test
    void aStartedRunIsRunningWithNoFinishStamp() {
        tracker.onStart(context());

        RunState state = tracker.get(key);
        assertNotNull(state);
        assertEquals("RUNNING", state.lastStatus());
        assertNotNull(state.lastStartedAt());
        assertNull(state.lastFinishedAt());
        assertNull(state.lastError());
    }

    @Test
    void aStoppedRunIsSuccessWithADuration() {
        ScheduledTaskObservationContext ctx = context();
        tracker.onStart(ctx);
        tracker.onStop(ctx);

        RunState state = tracker.get(key);
        assertEquals("SUCCESS", state.lastStatus());
        assertNotNull(state.lastFinishedAt());
        assertFalse(state.lastFinishedAt().isBefore(state.lastStartedAt()));
        assertNotNull(state.lastDurationMs());
        assertTrue(state.lastDurationMs() >= 0);
        assertNull(state.lastError());
    }

    @Test
    void aRestartPreservesThePreviousFinishStampWhileRunning() {
        ScheduledTaskObservationContext first = context();
        tracker.onStart(first);
        tracker.onStop(first);
        var finishedAt = tracker.get(key).lastFinishedAt();
        var finishedDuration = tracker.get(key).lastDurationMs();

        tracker.onStart(context());

        RunState state = tracker.get(key);
        assertEquals("RUNNING", state.lastStatus());
        assertEquals(finishedAt, state.lastFinishedAt(),
            "the previous run's finish stays visible while the new run is in flight");
        assertEquals(finishedDuration, state.lastDurationMs());
    }

    // ------------------------------------------------------------- failures

    @Test
    void aFailedRunKeepsTheExceptionsFirstLine() {
        ScheduledTaskObservationContext ctx = context();
        tracker.onStart(ctx);
        ctx.setError(new IllegalStateException("first line\nsecond line"));
        tracker.onError(ctx);
        tracker.onStop(ctx);

        RunState state = tracker.get(key);
        assertEquals("FAILED", state.lastStatus());
        assertEquals("IllegalStateException: first line", state.lastError(),
            "stack traces stay in the logs; only the bounded first line is kept");
    }

    @Test
    void anErrorSetAtStopAlsoMarksTheRunFailed() {
        ScheduledTaskObservationContext ctx = context();
        tracker.onStart(ctx);
        ctx.setError(new IllegalArgumentException("late failure"));
        tracker.onStop(ctx);

        RunState state = tracker.get(key);
        assertEquals("FAILED", state.lastStatus());
        assertEquals("IllegalArgumentException: late failure", state.lastError());
    }

    @Test
    void anErrorWithoutAStartRecordsNothing() {
        ScheduledTaskObservationContext ctx = context();
        ctx.setError(new RuntimeException("orphan"));
        tracker.onError(ctx);

        assertNull(tracker.get(key), "onError only annotates a run that already started");
    }

    @Test
    void aStopWithoutAStartStillRecordsAFinishedRun() {
        tracker.onStop(context());

        RunState state = tracker.get(key);
        assertNotNull(state);
        assertEquals("SUCCESS", state.lastStatus());
        assertNull(state.lastStartedAt());
        assertNull(state.lastDurationMs(), "no start stamp means no duration");
    }

    @Test
    void aMessagelessErrorReportsOnlyTheExceptionName() {
        ScheduledTaskObservationContext ctx = context();
        tracker.onStart(ctx);
        ctx.setError(new IllegalStateException());
        tracker.onError(ctx);
        tracker.onStop(ctx);

        assertEquals("IllegalStateException", tracker.get(key).lastError());
    }

    @Test
    void monsterErrorMessagesAreBounded() {
        ScheduledTaskObservationContext ctx = context();
        tracker.onStart(ctx);
        ctx.setError(new RuntimeException("x".repeat(2_000)));
        tracker.onError(ctx);
        tracker.onStop(ctx);

        assertEquals(500, tracker.get(key).lastError().length(),
            "error text is bounded so one pathological failure can't bloat the map");
    }

    // ------------------------------------------------------------- keying

    @Test
    void differentMethodsGetIndependentEntries() throws Exception {
        Method other = ScheduledJobRunTrackerTest.class.getDeclaredMethod("probe2");
        tracker.onStart(context());
        tracker.onStop(context());
        tracker.onStart(new ScheduledTaskObservationContext(other));

        String otherKey = ScheduledJobRunTrackerTest.class.getName() + ".probe2";
        assertEquals("SUCCESS", tracker.get(key).lastStatus());
        assertEquals("RUNNING", tracker.get(otherKey).lastStatus());
    }

    @Test
    void aJobThatNeverRanHasNoState() {
        assertNull(tracker.get("never.seen.Job.method"));
    }

    @SuppressWarnings("unused")
    private void probe2() {}
}
