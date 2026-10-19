package com.agenticai.core.admin.localauth;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The WebAuthn challenges handed out and not yet answered. In memory on purpose: a challenge lives
 * two minutes, belongs to one process (local mode is one core) and is worthless after a restart, so
 * a table would only add writes to the sign-in path.
 *
 * <p>Single use: {@link #take} removes the entry whether or not the caller then succeeds, so a
 * challenge can never be replayed or retried. Bounded: when {@code maxEntries} is reached the OLDEST
 * entries are dropped to make room, so an anonymous flood of {@code login-options} calls can crowd
 * out in-flight challenges (a nuisance, bounded by the two-minute lifetime) but can never grow the
 * heap. The id handed to the browser is lower-case hex, because the sign-in throttle folds
 * identifiers to lower case and an id that survives that unchanged keeps one challenge one bucket.
 */
final class PasskeyChallenges {

    enum Purpose {
        REGISTER,
        LOGIN
    }

    /** What was promised when the challenge was issued; the answer is verified against exactly this. */
    record Challenge(String id, Purpose purpose, String accountId, String origin, String rpId, byte[] bytes, Instant expiresAt) {}

    static final Duration TTL = Duration.ofMinutes(2);
    static final int DEFAULT_MAX_ENTRIES = 5_000;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final Clock clock;
    private final int maxEntries;
    private final Map<String, Challenge> entries = new LinkedHashMap<>();

    PasskeyChallenges(Clock clock) {
        this(clock, DEFAULT_MAX_ENTRIES);
    }

    PasskeyChallenges(Clock clock, int maxEntries) {
        this.clock = clock;
        this.maxEntries = Math.max(1, maxEntries);
    }

    /** Issues a fresh challenge. {@code accountId} is null for a sign-in (usernameless) challenge. */
    synchronized Challenge issue(Purpose purpose, String accountId, String origin, String rpId) {
        Instant now = clock.instant();
        purgeExpired(now);
        while (entries.size() >= maxEntries) {
            Iterator<String> oldest = entries.keySet().iterator();
            oldest.next();
            oldest.remove();
        }
        byte[] id = new byte[16];
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(id);
        RANDOM.nextBytes(bytes);
        Challenge challenge = new Challenge(HexFormat.of().formatHex(id), purpose, accountId, origin, rpId, bytes, now.plus(TTL));
        entries.put(challenge.id(), challenge);
        return challenge;
    }

    /** Removes and returns the challenge if it exists, is unexpired and was issued for {@code purpose}. */
    synchronized Optional<Challenge> take(String id, Purpose purpose) {
        if (id == null) {
            return Optional.empty();
        }
        Challenge challenge = entries.remove(id);
        if (challenge == null || challenge.purpose() != purpose || !challenge.expiresAt().isAfter(clock.instant())) {
            return Optional.empty();
        }
        return Optional.of(challenge);
    }

    synchronized int size() {
        return entries.size();
    }

    private void purgeExpired(Instant now) {
        entries.values().removeIf(c -> !c.expiresAt().isAfter(now));
    }
}
