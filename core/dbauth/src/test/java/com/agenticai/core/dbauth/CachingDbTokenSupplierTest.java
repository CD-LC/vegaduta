package com.agenticai.core.dbauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class CachingDbTokenSupplierTest {

    /** A clock the test moves by hand. */
    static final class MutableClock extends Clock {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");

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

        void advance(Duration d) {
            now = now.plus(d);
        }
    }

    /** Fake cloud: token-N valid for 15 minutes from the call; can be told to fail. */
    static final class FakeSource implements DbTokenSource {
        final MutableClock clock;
        final AtomicInteger calls = new AtomicInteger();
        volatile RuntimeException failure;

        FakeSource(MutableClock clock) {
            this.clock = clock;
        }

        @Override
        public DbToken fetch() {
            calls.incrementAndGet();
            if (failure != null) {
                throw failure;
            }
            return new DbToken(
                    "token-" + calls.get(), clock.instant().plus(Duration.ofMinutes(15)));
        }
    }

    private final MutableClock clock = new MutableClock();
    private final FakeSource source = new FakeSource(clock);
    private final CachingDbTokenSupplier supplier =
            new CachingDbTokenSupplier(source, Duration.ofMinutes(2), clock);

    @Test
    void reusesTheTokenInsideTheWindow() {
        assertThat(supplier.password()).isEqualTo("token-1");
        clock.advance(Duration.ofMinutes(12));
        assertThat(supplier.password()).isEqualTo("token-1");
        assertThat(source.calls.get()).isEqualTo(1);
    }

    @Test
    void refreshesBeforeTheTokenExpires() {
        supplier.password();
        clock.advance(Duration.ofMinutes(13).plusSeconds(1)); // 119s left < 2 min skew, still valid
        assertThat(supplier.password()).isEqualTo("token-2");
        assertThat(source.calls.get()).isEqualTo(2);
    }

    @Test
    void failedRefreshServesTheStillValidTokenThenPropagates() {
        supplier.password();
        source.failure = new IllegalStateException("IMDS unreachable");
        clock.advance(Duration.ofMinutes(14)); // inside the skew, not yet expired
        assertThat(supplier.password()).isEqualTo("token-1");

        clock.advance(Duration.ofMinutes(2)); // expired
        assertThatThrownBy(supplier::password)
                .isInstanceOf(DbAuthException.class)
                .hasMessageContaining("IMDS unreachable")
                .hasCauseInstanceOf(IllegalStateException.class);
    }

    @Test
    void failedRefreshWithAStillValidTokenIsNotRetriedOnEveryCall() {
        supplier.password();
        assertThat(source.calls.get()).isEqualTo(1);
        source.failure = new IllegalStateException("IdP unreachable");
        clock.advance(Duration.ofMinutes(14)); // inside the skew, 1 minute of validity left

        for (int i = 0; i < 20; i++) {
            assertThat(supplier.password()).isEqualTo("token-1");
        }
        assertThat(source.calls.get()).as("one failed attempt, then backoff").isEqualTo(2);

        clock.advance(CachingDbTokenSupplier.RETRY_BACKOFF.plusSeconds(1));
        assertThat(supplier.password()).isEqualTo("token-1");
        assertThat(source.calls.get()).as("retried after the backoff").isEqualTo(3);

        source.failure = null; // the IdP is back: the next retry window picks up a fresh token
        clock.advance(CachingDbTokenSupplier.RETRY_BACKOFF.plusSeconds(1));
        assertThat(supplier.password()).isEqualTo("token-4");
    }

    @Test
    void backoffNeverOutlivesTheTokenSoAnExpiredTokenFailsLoudly() {
        supplier.password();
        source.failure = new IllegalStateException("IdP unreachable");
        clock.advance(Duration.ofMinutes(14).plusSeconds(50)); // 10s of validity left
        assertThat(supplier.password()).isEqualTo("token-1");
        clock.advance(Duration.ofSeconds(11)); // expired, well inside the 30s backoff
        assertThatThrownBy(supplier::password).isInstanceOf(DbAuthException.class);
    }

    @Test
    void firstFetchFailurePropagatesAndNextCallRetries() {
        source.failure = new IllegalStateException("no credentials");
        assertThatThrownBy(supplier::password).isInstanceOf(DbAuthException.class);
        source.failure = null;
        assertThat(supplier.password()).isEqualTo("token-2");
    }

    @Test
    void shortLivedTokenIsNotRefetchedOnEveryCall() {
        DbTokenSource oneMinute =
                new DbTokenSource() {
                    int n;

                    @Override
                    public DbToken fetch() {
                        return new DbToken(
                                "t" + (++n), clock.instant().plus(Duration.ofMinutes(1)));
                    }
                };
        CachingDbTokenSupplier s =
                new CachingDbTokenSupplier(oneMinute, Duration.ofMinutes(2), clock);
        assertThat(s.password()).isEqualTo("t1");
        clock.advance(Duration.ofSeconds(20)); // refresh point is half the lifetime: 30s
        assertThat(s.password()).isEqualTo("t1");
        clock.advance(Duration.ofSeconds(20));
        assertThat(s.password()).isEqualTo("t2");
    }

    @Test
    void tokenIsNeverPrinted() {
        assertThat(new DbToken("secret-value", Instant.EPOCH).toString())
                .doesNotContain("secret-value");
    }
}
