package com.agenticai.core.providers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.GoogleCredentials;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Covers {@link VertexAccessTokenProvider}'s token-refresh critical section -
 * the part that was a {@code synchronized (credentials)} block until the
 * virtual-thread pinning fix (2026-07-29) replaced it with a per-cache-key
 * {@link java.util.concurrent.locks.ReentrantLock}.
 *
 * <p><b>Why these specific tests.</b> The pinning problem itself is not
 * unit-testable - it only manifests as carrier-thread starvation on JDK 21,
 * and JEP 491 makes it unobservable on the JDK 24 this repo builds with. What
 * IS testable, and what a careless "swap synchronized for a lock" would break,
 * is the surrounding contract:
 * <ul>
 *   <li>refreshes for one credential are still mutually exclusive (a lock that
 *       is per-call instead of per-key would silently lose that),</li>
 *   <li>refreshes for DIFFERENT credentials are still independent - the old
 *       monitor was the {@code GoogleCredentials} instance, i.e. one per cache
 *       key, so a single shared lock would be a real regression (every tenant's
 *       BYOK token refresh queueing behind every other one),</li>
 *   <li>the lock is released on the failure path. {@code synchronized} released
 *       its monitor for free on exception; a {@code lock()} without
 *       {@code finally unlock()} deadlocks the next caller forever, which is
 *       the single worst way to get this refactor wrong.</li>
 * </ul>
 *
 * <p>Exercises the private {@code accessToken(String, Supplier)} seam by
 * reflection rather than the public {@code accessTokenForPoolSlot} /
 * {@code accessTokenForCredential} entry points: those resolve a real
 * service-account key from disk or JSON and would then perform a real token
 * exchange against Google. Reflecting past the credential LOADER (and only the
 * loader) keeps the actual locking code under test with zero network.
 */
class VertexAccessTokenProviderTest {

    private VertexAccessTokenProvider provider;
    private Method accessToken;

    @BeforeEach
    void setUp() throws Exception {
        provider = new VertexAccessTokenProvider(new VertexPoolProperties());
        accessToken = VertexAccessTokenProvider.class.getDeclaredMethod("accessToken", String.class, Supplier.class);
        accessToken.setAccessible(true);
    }

    private String call(String cacheKey, Supplier<GoogleCredentials> loader) throws Exception {
        try {
            return (String) accessToken.invoke(provider, cacheKey, loader);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw e;
        }
    }

    // --- basic behaviour preserved -------------------------------------------

    @Test
    void returnsTheRefreshedTokenValue() throws Exception {
        assertEquals("token-1", call("pool:p1", () -> new FakeCredentials("token-1")));
    }

    @Test
    void loadsTheCredentialOncePerCacheKeyAndReusesItAcrossCalls() throws Exception {
        AtomicInteger loads = new AtomicInteger();
        Supplier<GoogleCredentials> loader = () -> {
            loads.incrementAndGet();
            return new FakeCredentials("token");
        };

        call("pool:p1", loader);
        call("pool:p1", loader);
        call("pool:p1", loader);

        assertEquals(1, loads.get(), "the cached GoogleCredentials must be reused, not reloaded per call");
    }

    // --- token cache ----------------------------------------------------------
    // Reusing the credential OBJECT (above) is not the same as reusing its
    // TOKEN. Until 2026-07-29 the former held and the latter did not, so every
    // Vertex chat/image/video call paid a blocking OAuth2 round-trip. These
    // assert round-trip COUNTS - asserting the returned value alone passes
    // just as happily against the broken version.

    @Test
    void secondCallReusesTheCachedTokenInsteadOfRefreshing() throws Exception {
        FakeCredentials credentials = new FakeCredentials("token");
        Supplier<GoogleCredentials> loader = () -> credentials;

        assertEquals("token", call("pool:p1", loader));
        assertEquals("token", call("pool:p1", loader));

        assertEquals(1, credentials.refreshes.get(),
            "a cached, unexpired token must not trigger a second OAuth2 round-trip");
    }

    @Test
    void staysCachedAcrossManyCalls() throws Exception {
        FakeCredentials credentials = new FakeCredentials("token");
        Supplier<GoogleCredentials> loader = () -> credentials;

        for (int i = 0; i < 25; i++) {
            assertEquals("token", call("pool:p1", loader));
        }

        assertEquals(1, credentials.refreshes.get(), "25 calls should cost exactly one token fetch");
    }

    @Test
    void refreshesAgainOnceTheTokenIsInsideTheExpiryMargin() throws Exception {
        FakeCredentials credentials = new FakeCredentials("token");
        // Inside google-auth's 3-minute expiration margin, so each call is due one.
        credentials.tokenLifetime = Duration.ofSeconds(30);
        Supplier<GoogleCredentials> loader = () -> credentials;

        call("pool:p1", loader);
        call("pool:p1", loader);

        assertEquals(2, credentials.refreshes.get(),
            "a token inside the expiry margin must still be refreshed - caching must not mean serving a stale token");
    }

    @Test
    void cachedTokensAreNotSharedAcrossCacheKeys() throws Exception {
        FakeCredentials a = new FakeCredentials("token-a");
        FakeCredentials b = new FakeCredentials("token-b");

        assertEquals("token-a", call("pool:a", () -> a));
        assertEquals("token-b", call("pool:b", () -> b));
        assertEquals("token-a", call("pool:a", () -> a));
        assertEquals("token-b", call("pool:b", () -> b));

        assertEquals(1, a.refreshes.get());
        assertEquals(1, b.refreshes.get());
    }

    @Test
    void concurrentCallersShareOneRefresh() throws Exception {
        FakeCredentials credentials = new FakeCredentials("token");
        // Latency widens the window in which a broken cache lets every caller
        // start its own round-trip.
        credentials.holdMillis = 150;
        Supplier<GoogleCredentials> loader = () -> credentials;

        int threads = 16;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        for (int i = 0; i < threads; i++) {
            Thread.ofPlatform().start(() -> runQuietly(() -> {
                start.await();
                assertEquals("token", call("pool:p1", loader));
            }, done));
        }
        start.countDown();

        assertTrue(done.await(30, TimeUnit.SECONDS), "contended callers should all complete");
        assertEquals(1, credentials.refreshes.get(),
            "the per-key lock plus a working cache should collapse 16 callers to a single fetch");
    }

    // --- lock semantics -------------------------------------------------------

    @Test
    void refreshesForTheSameCacheKeyNeverOverlap() throws Exception {
        FakeCredentials credentials = new FakeCredentials("token");
        credentials.holdMillis = 25;
        Supplier<GoogleCredentials> loader = () -> credentials;

        // Prime the cache first, so the threads below contend on the refresh
        // lock rather than on computeIfAbsent's own mapping lock.
        call("pool:p1", loader);

        int threads = 8;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        for (int i = 0; i < threads; i++) {
            Thread.ofPlatform().start(() -> {
                try {
                    start.await();
                    call("pool:p1", loader);
                } catch (Exception ignored) {
                    // Failure shows up as concurrentPeak below, not here.
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();

        assertTrue(done.await(30, TimeUnit.SECONDS), "contended refreshes should all complete");
        assertEquals(1, credentials.concurrentPeak.get(),
            "two threads were inside refreshAccessToken() for the same credential at once - the critical section is gone");
    }

    @Test
    void refreshesForDifferentCacheKeysDoNotBlockEachOther() throws Exception {
        // Each credential's refresh waits for the OTHER one to have entered its
        // refresh. If both keys shared one lock this deadlocks and the latch
        // times out; with a lock per cache key both proceed.
        CountDownLatch aEntered = new CountDownLatch(1);
        CountDownLatch bEntered = new CountDownLatch(1);
        FakeCredentials a = new FakeCredentials("token-a");
        FakeCredentials b = new FakeCredentials("token-b");
        a.onRefresh = () -> {
            aEntered.countDown();
            awaitOrFail(bEntered);
        };
        b.onRefresh = () -> {
            bEntered.countDown();
            awaitOrFail(aEntered);
        };

        CountDownLatch done = new CountDownLatch(2);
        Thread.ofPlatform().start(() -> runQuietly(() -> call("pool:a", () -> a), done));
        Thread.ofPlatform().start(() -> runQuietly(() -> call("pool:b", () -> b), done));

        assertTrue(done.await(15, TimeUnit.SECONDS),
            "refreshes for two different cache keys serialized against each other - the lock is not per key");
    }

    @Test
    void aFailedRefreshReleasesTheLockSoTheNextCallerIsNotStuck() throws Exception {
        FakeCredentials failing = new FakeCredentials("unused");
        failing.failure = new IOException("token endpoint unreachable");

        VertexCredentialException thrown = assertInstanceOf(
            VertexCredentialException.class,
            catching(() -> call("pool:p1", () -> failing))
        );
        assertTrue(thrown.getMessage().contains("Could not refresh"));

        // Would hang forever (until the 10s guard below fires) if lock() had no
        // matching unlock() in a finally block.
        CountDownLatch done = new CountDownLatch(1);
        Thread.ofPlatform().start(() -> runQuietly(() -> call("pool:p1", () -> new FakeCredentials("token-2")), done));
        assertTrue(done.await(10, TimeUnit.SECONDS), "the refresh lock was not released after a failed refresh");
    }

    @Test
    void aFailedRefreshEvictsTheCachedCredentialSoTheNextCallReloadsIt() throws Exception {
        FakeCredentials failing = new FakeCredentials("unused");
        failing.failure = new IOException("boom");
        FakeCredentials reloaded = new FakeCredentials("token-2");

        catching(() -> call("pool:p1", () -> failing));

        assertEquals("token-2", call("pool:p1", () -> reloaded),
            "a broken credential must be dropped from the cache, not served again");
    }

    // --- unrelated surface, kept honest --------------------------------------

    @Test
    void aDisabledPoolExposesNoUsableSlots() {
        assertEquals(List.of(), provider.usablePoolSlots());
        assertFalse(provider.isPoolConfigured());
    }

    // --- helpers --------------------------------------------------------------

    private static void awaitOrFail(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("timed out waiting for the other refresh to start");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static void runQuietly(ThrowingRunnable body, CountDownLatch done) {
        try {
            body.run();
        } catch (Exception ignored) {
            // The assertion of interest is whether `done` counts down in time.
        } finally {
            done.countDown();
        }
    }

    private static RuntimeException catching(ThrowingRunnable body) {
        try {
            body.run();
            throw new AssertionError("expected a VertexCredentialException");
        } catch (RuntimeException e) {
            return e;
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    /**
     * A {@link GoogleCredentials} whose refresh is instrumented instead of
     * hitting Google. Overriding {@code refreshAccessToken()} is the same
     * extension point {@code ServiceAccountCredentials} uses, so the caching
     * path under test is the production one with only the HTTP call faked.
     *
     * <p>{@code getAccessToken()} is {@code final} upstream and reads instance
     * state that ONLY {@code refresh()}/{@code refreshIfExpired()} publish -
     * {@code refreshAccessToken()} returns a token without storing it. Until
     * 2026-07-29 {@code accessToken} called the latter, so that state stayed
     * null and the cache never hit; {@link #refreshes} is what pins that down.
     */
    private static final class FakeCredentials extends GoogleCredentials {

        private final String tokenValue;
        private final AtomicInteger concurrent = new AtomicInteger();

        final AtomicInteger concurrentPeak = new AtomicInteger();
        /** Total round-trips - a value-only assertion cannot tell a working cache from a broken one. */
        final AtomicInteger refreshes = new AtomicInteger();
        long holdMillis;
        IOException failure;
        Runnable onRefresh;
        /** Comfortably outside google-auth's 3-minute expiration margin unless a test shortens it. */
        Duration tokenLifetime = Duration.ofHours(1);

        FakeCredentials(String tokenValue) {
            this.tokenValue = tokenValue;
        }

        @Override
        public AccessToken refreshAccessToken() throws IOException {
            concurrentPeak.accumulateAndGet(concurrent.incrementAndGet(), Math::max);
            refreshes.incrementAndGet();
            try {
                if (onRefresh != null) {
                    onRefresh.run();
                }
                if (holdMillis > 0) {
                    try {
                        Thread.sleep(holdMillis);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                if (failure != null) {
                    throw failure;
                }
                return new AccessToken(tokenValue, Date.from(Instant.now().plus(tokenLifetime)));
            } finally {
                concurrent.decrementAndGet();
            }
        }
    }
}
