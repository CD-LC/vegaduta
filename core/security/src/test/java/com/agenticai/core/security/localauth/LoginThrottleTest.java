package com.agenticai.core.security.localauth;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticai.core.security.localauth.LocalSigningKeyStoreTest.MutableClock;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class LoginThrottleTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
    private final LoginThrottle throttle =
            new LoginThrottle(3, 100, Duration.ofSeconds(30), Duration.ofMinutes(10), Duration.ofMinutes(30), 50, clock);

    private void fail(int times, String account) {
        for (int i = 0; i < times; i++) {
            throttle.recordFailure("10.0.0.1", account);
        }
    }

    @Test
    void ordinaryTyposAreNotThrottled() {
        fail(2, "owner@example.test");
        assertThat(throttle.check("10.0.0.1", "owner@example.test").allowed()).isTrue();
    }

    @Test
    void theAccountLocksAtTheThresholdAndReportsHowLongToWait() {
        fail(3, "owner@example.test");

        LoginThrottle.Decision d = throttle.check("10.0.0.1", "owner@example.test");
        assertThat(d.allowed()).isFalse();
        assertThat(d.retryAfterSeconds()).isBetween(1L, 30L);
    }

    @Test
    void theLockIsPerAccountAndAlsoBlocksOtherAddressesForThatAccount() {
        fail(3, "owner@example.test");

        assertThat(throttle.check("203.0.113.9", "owner@example.test").allowed()).isFalse();
        assertThat(throttle.check("10.0.0.1", "someone-else@example.test").allowed()).isTrue();
    }

    @Test
    void theLockExpiresAndFurtherFailuresBackOffExponentially() {
        fail(3, "a");
        clock.advance(Duration.ofSeconds(31));
        assertThat(throttle.check("10.0.0.1", "a").allowed()).isTrue();

        fail(1, "a"); // 4th failure: over=1 -> 60s
        LoginThrottle.Decision d = throttle.check("10.0.0.1", "a");
        assertThat(d.allowed()).isFalse();
        assertThat(d.retryAfterSeconds()).isBetween(31L, 60L);
    }

    @Test
    void theLockIsCappedSoTheOwnerIsNeverLockedOutForGood() {
        fail(40, "a");
        LoginThrottle.Decision d = throttle.check("10.0.0.1", "a");
        assertThat(d.retryAfterSeconds()).isLessThanOrEqualTo(600L);
    }

    @Test
    void aSuccessClearsTheCounters() {
        fail(2, "a");
        throttle.recordSuccess("10.0.0.1", "a");
        fail(2, "a");

        assertThat(throttle.check("10.0.0.1", "a").allowed()).isTrue();
    }

    @Test
    void oldFailuresDecayAfterTheWindow() {
        fail(2, "a");
        clock.advance(Duration.ofMinutes(31));
        fail(2, "a");

        assertThat(throttle.check("10.0.0.1", "a").allowed()).isTrue();
    }

    @Test
    void identifiersAreCaseAndWhitespaceInsensitive() {
        fail(3, "  Owner@Example.Test ");
        assertThat(throttle.check("10.0.0.1", "owner@example.test").allowed()).isFalse();
    }

    @Test
    void memoryIsBoundedAgainstAFloodOfDistinctNames() {
        for (int i = 0; i < 500; i++) {
            throttle.recordFailure("10.0.0." + (i % 250), "user" + i);
        }
        assertThat(throttle.size()).isLessThanOrEqualTo(60);
    }

    @Test
    void anAddressThatSignedInBeforeStillGetsCheckedWhileTheAccountIsLocked() {
        throttle.recordSuccess("10.0.0.1", "owner@example.test");
        fail(3, "owner@example.test");
        assertThat(throttle.check("10.0.0.1", "owner@example.test").allowed()).isFalse();

        LoginThrottle.LoginDecision known = throttle.checkLogin("10.0.0.1", "owner@example.test");
        assertThat(known.allowed()).isTrue();
        assertThat(known.exempt()).isTrue();
        // a stranger, and a first sign-in from a new address, stay refused
        assertThat(throttle.checkLogin("203.0.113.9", "owner@example.test").allowed()).isFalse();
    }

    @Test
    void theExemptionIsRateLimitedAndExpires() {
        throttle.recordSuccess("10.0.0.1", "a");
        fail(3, "a");

        assertThat(throttle.checkLogin("10.0.0.1", "a").allowed()).isTrue();
        assertThat(throttle.checkLogin("10.0.0.1", "a").allowed()).as("one probe per 2 seconds").isFalse();
        clock.advance(Duration.ofSeconds(3));
        assertThat(throttle.checkLogin("10.0.0.1", "a").allowed()).isTrue();

        clock.advance(Duration.ofDays(31));
        fail(3, "a");
        assertThat(throttle.checkLogin("10.0.0.1", "a").allowed()).as("known-good ages out").isFalse();
    }

    @Test
    void knownGoodMemoryIsBounded() {
        for (int i = 0; i < 500; i++) {
            throttle.recordSuccess("10.0.0." + i, "user" + i);
        }
        // maxEntries is 50 in this fixture; the map clears rather than growing without limit.
        assertThat(throttle.checkLogin("10.0.0.499", "user499").allowed()).isTrue();
    }
}
