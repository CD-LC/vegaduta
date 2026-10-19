package com.agenticai.core.tenancy.demo;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** Activity-based expiry (migration V134) - see {@link DemoSession#touchActivity}. */
class DemoSessionTest {

    private static DemoSession newSession(Instant createdAt, Instant expiresAt) {
        return new DemoSession(
            "hash", java.util.UUID.randomUUID(), "demo_test", null, "127.0.0.1",
            null, createdAt, expiresAt
        );
    }

    @Test
    void lastActivityAtDefaultsToCreatedAt() {
        Instant createdAt = Instant.parse("2026-07-26T00:00:00Z");
        Instant expiresAt = createdAt.plus(Duration.ofHours(24));
        DemoSession session = newSession(createdAt, expiresAt);

        assertThat(session.getLastActivityAt()).isEqualTo(createdAt);
    }

    @Test
    void touchActivityBumpsLastActivityAndExtendsExpiry() {
        Instant createdAt = Instant.parse("2026-07-26T00:00:00Z");
        Instant shortExpiry = createdAt.plus(Duration.ofHours(24)); // the never-touched grace window
        DemoSession session = newSession(createdAt, shortExpiry);

        Instant activityAt = createdAt.plus(Duration.ofHours(2));
        Instant fullLifetimeExpiry = activityAt.plus(Duration.ofDays(5)); // demo.lifetime-days

        session.touchActivity(activityAt, fullLifetimeExpiry);

        assertThat(session.getLastActivityAt()).isEqualTo(activityAt);
        assertThat(session.getExpiresAt()).isEqualTo(fullLifetimeExpiry);
    }

    @Test
    void touchActivityNeverMovesExpiryBackwards() {
        Instant createdAt = Instant.parse("2026-07-26T00:00:00Z");
        Instant alreadyExtended = createdAt.plus(Duration.ofDays(5));
        DemoSession session = newSession(createdAt, alreadyExtended);

        // A stale/out-of-order call (e.g. a retried request computed against
        // an earlier "now") proposing an earlier expiry than what's already
        // stored must not shorten it.
        Instant retriedActivityAt = createdAt.plus(Duration.ofMinutes(1));
        Instant proposedButEarlier = retriedActivityAt.plus(Duration.ofDays(1));
        assertThat(proposedButEarlier).isBefore(alreadyExtended);

        session.touchActivity(retriedActivityAt, proposedButEarlier);

        assertThat(session.getExpiresAt()).isEqualTo(alreadyExtended);
        // lastActivityAt still moves forward though - it's a pure "when did
        // something last happen" mark, independent of the expiry guard.
        assertThat(session.getLastActivityAt()).isEqualTo(retriedActivityAt);
    }

    @Test
    void isExpiredUsesExpiresAtRegardlessOfActivity() {
        Instant createdAt = Instant.parse("2026-07-26T00:00:00Z");
        Instant expiresAt = createdAt.plus(Duration.ofHours(24));
        DemoSession session = newSession(createdAt, expiresAt);

        assertThat(session.isExpired(createdAt.plus(Duration.ofHours(23)))).isFalse();
        assertThat(session.isExpired(createdAt.plus(Duration.ofHours(25)))).isTrue();
    }
}
