package com.agenticai.core.security.localauth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Brute-force throttling for the local login: failed attempts are counted per source address and
 * per account, and once a bucket passes its threshold further attempts are refused for a lock that
 * doubles with every extra failure up to a cap, so guessing is slowed from thousands per minute to a
 * handful per hour while a person who mistypes twice notices nothing.
 *
 * <p>Two buckets on purpose. The per-account bucket is what stops a distributed guess at the owner's
 * password; its threshold is low but its lock is capped at minutes, so an attacker can annoy the
 * owner for minutes, never lock them out for good: an address that already signed the account in
 * keeps being checked while the lock is on ({@link #checkLogin}), at a bounded rate and without
 * extending the lock. The per-address bucket stops one client walking a list of accounts. A success clears both. Counters decay: a bucket with no failure for {@code
 * window} starts again from zero.
 *
 * <p>State is in memory and per process, which matches the deployment this mode serves (one core).
 * It is bounded: past {@code maxEntries} the oldest buckets are dropped, so a flood of distinct
 * usernames cannot grow the heap without limit.
 */
public final class LoginThrottle {

    /** Outcome of {@link #check}: when {@code allowed} is false, retry after the given seconds. */
    public record Decision(boolean allowed, long retryAfterSeconds) {
        static final Decision OK = new Decision(true, 0);
    }

    /**
     * Outcome of {@link #checkLogin}. {@code exempt} means a lock is active but the attempt comes
     * from an address that already signed this account in: the credential is still checked (a
     * correct one is accepted), a wrong one must be answered as throttled WITHOUT being recorded, so
     * the lock cannot be extended by whoever is guessing.
     */
    public record LoginDecision(boolean allowed, long retryAfterSeconds, boolean exempt) {}

    private static final Duration KNOWN_GOOD_TTL = Duration.ofDays(30);
    private static final Duration EXEMPT_SPACING = Duration.ofSeconds(2);

    private static final class Bucket {
        int failures;
        Instant lastFailure = Instant.EPOCH;
        Instant lockedUntil = Instant.EPOCH;
    }

    private final int accountThreshold;
    private final int addressThreshold;
    private final Duration baseLock;
    private final Duration maxLock;
    private final Duration window;
    private final int maxEntries;
    private final Clock clock;
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();
    /** "address|account" to the time of the last successful sign-in. Bounded like the buckets. */
    private final Map<String, Instant> knownGood = new ConcurrentHashMap<>();
    /** account to the time of the last exempt (locked but known-good) verification. */
    private final Map<String, Instant> lastExempt = new ConcurrentHashMap<>();

    public LoginThrottle(Clock clock) {
        this(5, 20, Duration.ofSeconds(30), Duration.ofMinutes(15), Duration.ofMinutes(30), 10_000, clock);
    }

    public LoginThrottle(
            int accountThreshold,
            int addressThreshold,
            Duration baseLock,
            Duration maxLock,
            Duration window,
            int maxEntries,
            Clock clock) {
        this.accountThreshold = accountThreshold;
        this.addressThreshold = addressThreshold;
        this.baseLock = baseLock;
        this.maxLock = maxLock;
        this.window = window;
        this.maxEntries = maxEntries;
        this.clock = clock;
    }

    /** Whether an attempt from {@code address} for {@code account} may be evaluated at all. */
    public Decision check(String address, String account) {
        Instant now = clock.instant();
        long wait = Math.max(remaining("a:" + norm(address), now), remaining("u:" + norm(account), now));
        return wait <= 0 ? Decision.OK : new Decision(false, wait);
    }

    /**
     * Like {@link #check} for a password sign-in, with one exception so a lock cannot become a
     * lasting denial of service against the owner: while a lock is active, an address that has
     * already signed in as this account (last 30 days) may still have its credential verified, at
     * most once every 2 seconds per account. Anyone else, including a first sign-in from a new
     * address, is refused exactly as before.
     */
    public LoginDecision checkLogin(String address, String account) {
        Decision d = check(address, account);
        if (d.allowed()) {
            return new LoginDecision(true, 0, false);
        }
        Instant now = clock.instant();
        Instant good = knownGood.get(pairKey(address, account));
        if (good != null && good.plus(KNOWN_GOOD_TTL).isAfter(now) && takeExemptSlot(norm(account), now)) {
            return new LoginDecision(true, d.retryAfterSeconds(), true);
        }
        return new LoginDecision(false, d.retryAfterSeconds(), false);
    }

    private boolean takeExemptSlot(String account, Instant now) {
        boolean[] granted = {false};
        lastExempt.compute(
                account,
                (k, last) -> {
                    if (last == null || last.plus(EXEMPT_SPACING).isBefore(now)) {
                        granted[0] = true;
                        return now;
                    }
                    return last;
                });
        return granted[0];
    }

    private static String pairKey(String address, String account) {
        return norm(address) + "|" + norm(account);
    }

    public void recordFailure(String address, String account) {
        fail("a:" + norm(address), addressThreshold);
        fail("u:" + norm(account), accountThreshold);
        evictIfNeeded();
    }

    public void recordSuccess(String address, String account) {
        buckets.remove("a:" + norm(address));
        buckets.remove("u:" + norm(account));
        if (knownGood.size() >= maxEntries) {
            Instant now = clock.instant();
            knownGood.entrySet().removeIf(e -> e.getValue().plus(KNOWN_GOOD_TTL).isBefore(now));
            if (knownGood.size() >= maxEntries) {
                knownGood.clear();
            }
        }
        knownGood.put(pairKey(address, account), clock.instant());
    }

    /** Number of live buckets. For tests and metrics. */
    public int size() {
        return buckets.size();
    }

    private long remaining(String key, Instant now) {
        Bucket bucket = buckets.get(key);
        if (bucket == null) {
            return 0;
        }
        synchronized (bucket) {
            long seconds = Duration.between(now, bucket.lockedUntil).toSeconds();
            if (seconds < 0) {
                return 0;
            }
            // Round up so "retry after 0" is never reported while still locked.
            return bucket.lockedUntil.isAfter(now) ? Math.max(1, seconds) : 0;
        }
    }

    private void fail(String key, int threshold) {
        Instant now = clock.instant();
        Bucket bucket = buckets.computeIfAbsent(key, k -> new Bucket());
        synchronized (bucket) {
            if (bucket.lastFailure.plus(window).isBefore(now)) {
                bucket.failures = 0;
            }
            bucket.failures++;
            bucket.lastFailure = now;
            int over = bucket.failures - threshold;
            if (over >= 0) {
                long factor = 1L << Math.min(over, 20);
                Duration lock = baseLock.multipliedBy(factor);
                if (lock.compareTo(maxLock) > 0 || lock.isNegative()) {
                    lock = maxLock;
                }
                bucket.lockedUntil = now.plus(lock);
            }
        }
    }

    private void evictIfNeeded() {
        if (buckets.size() <= maxEntries) {
            return;
        }
        Instant now = clock.instant();
        buckets.entrySet().removeIf(e -> {
            synchronized (e.getValue()) {
                return e.getValue().lastFailure.plus(window).isBefore(now)
                        && !e.getValue().lockedUntil.isAfter(now);
            }
        });
        if (buckets.size() > maxEntries) {
            buckets.entrySet().stream()
                    .sorted(Comparator.comparing(e -> e.getValue().lastFailure))
                    .limit(buckets.size() - maxEntries / 2)
                    .map(Map.Entry::getKey)
                    .toList()
                    .forEach(buckets::remove);
        }
    }

    private static String norm(String value) {
        if (value == null) {
            return "";
        }
        String v = value.trim().toLowerCase(Locale.ROOT);
        return v.length() > 320 ? v.substring(0, 320) : v;
    }
}
