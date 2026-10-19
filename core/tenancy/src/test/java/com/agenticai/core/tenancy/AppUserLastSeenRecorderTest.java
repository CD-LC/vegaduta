package com.agenticai.core.tenancy;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * V475 last-seen stamping: throttled per user, the UPDATE carries its own 5-minute predicate (so
 * replicas cannot multiply writes), and nothing the database or the executor does can surface to
 * the request thread.
 */
class AppUserLastSeenRecorderTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final Executor direct = Runnable::run;
    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-09T10:00:00Z"));
    private final AppUserLastSeenRecorder recorder =
            new AppUserLastSeenRecorder(jdbc, direct, clock);

    @Test
    void firstRequestStampsAndRepeatsInsideTheWindowDoNot() {
        recorder.recordSeen("acme", "kc-1");
        recorder.recordSeen("acme", "kc-1");
        clock.advanceSeconds(299);
        recorder.recordSeen("acme", "kc-1");

        verify(jdbc, times(1)).update(AppUserLastSeenRecorder.UPDATE_SQL, "kc-1");
    }

    @Test
    void stampsAgainOnceTheWindowHasPassedAndTracksUsersIndependently() {
        recorder.recordSeen("acme", "kc-1");
        recorder.recordSeen("acme", "kc-2");
        clock.advanceSeconds(301);
        recorder.recordSeen("acme", "kc-1");

        verify(jdbc, times(2)).update(AppUserLastSeenRecorder.UPDATE_SQL, "kc-1");
        verify(jdbc, times(1)).update(AppUserLastSeenRecorder.UPDATE_SQL, "kc-2");
    }

    @Test
    void theUpdateItselfIsConditionalSoReplicasCannotWriteMoreOftenThanTheWindow() {
        org.junit.jupiter.api.Assertions.assertTrue(
                AppUserLastSeenRecorder.UPDATE_SQL.contains(
                        "last_seen_at IS NULL OR last_seen_at < now() - interval '5 minutes'"));
        org.junit.jupiter.api.Assertions.assertTrue(
                AppUserLastSeenRecorder.UPDATE_SQL.contains("control_plane.app_user"));
    }

    @Test
    void aDatabaseFailureIsSwallowedAndTheNextRequestRetries() {
        when(jdbc.update(anyString(), eq("kc-1"))).thenThrow(new RuntimeException("db down"));

        assertDoesNotThrow(() -> recorder.recordSeen("acme", "kc-1"));
        assertDoesNotThrow(() -> recorder.recordSeen("acme", "kc-1"));

        // The failed stamp released the guard, so the second request tried again.
        verify(jdbc, times(2)).update(AppUserLastSeenRecorder.UPDATE_SQL, "kc-1");
    }

    @Test
    void aFullQueueNeverThrowsToTheCaller() {
        Executor full =
                command -> {
                    throw new RejectedExecutionException("queue full");
                };
        AppUserLastSeenRecorder saturated = new AppUserLastSeenRecorder(jdbc, full, clock);

        assertDoesNotThrow(() -> saturated.recordSeen("acme", "kc-1"));
        verify(jdbc, never()).update(anyString(), anyString());
    }

    @Test
    void blankSubjectsAreIgnored() {
        recorder.recordSeen("acme", null);
        recorder.recordSeen("acme", " ");
        verify(jdbc, never()).update(anyString(), anyString());
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

        void advanceSeconds(long seconds) {
            now = now.plusSeconds(seconds);
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
