package com.agenticai.core.security.ticket;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Replay guard for short-lived admission tickets (the realtime-voice and Twilio relay WebSocket
 * tickets): a ticket that has been accepted once is refused the second time, until it would have
 * expired anyway.
 *
 * <p>The tickets are unforgeable (AES-GCM under the platform key) and expire in minutes, but a
 * ticket that leaks into a proxy access log or a browser history is otherwise good for a second
 * connection until it expires. Burning it on first use removes that window.
 *
 * <p>What is stored is the SHA-256 of the ticket, never the ticket, so a heap dump of this table
 * hands out nothing usable. Entries live exactly as long as the ticket they shadow, so the table
 * is bounded by (tickets minted per TTL). It is also hard-capped: when it is full of unexpired
 * entries, new tickets are REFUSED rather than the guard being switched off, so an attacker
 * cannot flush it to buy a replay.
 *
 * <p>The state is per JVM. With several core replicas behind a load balancer without stickiness a
 * ticket could be replayed once per replica within its TTL; it still cannot be replayed on the
 * replica that already accepted it, and the ticket itself stays bound to one tenant, agent and
 * subject. That is strictly stronger than no guard and needs no new infrastructure.
 */
public final class SingleUseTicketRegistry {

    private static final int DEFAULT_MAX_ENTRIES = 100_000;
    private static final long SWEEP_INTERVAL_SECONDS = 60;

    private final ConcurrentHashMap<String, Long> consumedUntilEpochSeconds = new ConcurrentHashMap<>();
    private final Clock clock;
    private final int maxEntries;
    private volatile long lastSweepEpochSeconds;

    public SingleUseTicketRegistry() {
        this(Clock.systemUTC(), DEFAULT_MAX_ENTRIES);
    }

    public SingleUseTicketRegistry(Clock clock, int maxEntries) {
        this.clock = clock;
        this.maxEntries = Math.max(1, maxEntries);
    }

    /**
     * Burns {@code ticket}. True only the first time it is presented (and only while there is room
     * to remember it); false for a replay or when the table is full of live entries.
     *
     * @param expiresAtEpochSeconds when the ticket expires on its own; the entry is dropped then
     */
    public boolean tryConsume(String ticket, long expiresAtEpochSeconds) {
        if (ticket == null || ticket.isEmpty()) {
            return false;
        }
        long now = clock.instant().getEpochSecond();
        // Expired entries are dropped at most once a minute, so the table tracks the tickets still
        // live rather than sitting at its cap until a flush is forced.
        if (now - lastSweepEpochSeconds >= SWEEP_INTERVAL_SECONDS) {
            lastSweepEpochSeconds = now;
            consumedUntilEpochSeconds.values().removeIf(until -> until < now);
        }
        if (consumedUntilEpochSeconds.size() >= maxEntries) {
            consumedUntilEpochSeconds.values().removeIf(until -> until < now);
            if (consumedUntilEpochSeconds.size() >= maxEntries) {
                return false;
            }
        }
        String key = fingerprint(ticket);
        // Keep the entry a little past expiry so a ticket presented right at its edge is still caught.
        Long previous = consumedUntilEpochSeconds.putIfAbsent(key, expiresAtEpochSeconds + 5);
        if (previous == null) {
            return true;
        }
        if (previous < now) {
            // The remembered use has itself expired; the ticket is expired too, so the caller's own
            // expiry check rejects it. Refresh the entry and report it as already seen.
            consumedUntilEpochSeconds.replace(key, previous, expiresAtEpochSeconds + 5);
        }
        return false;
    }

    /** Entries currently remembered. For tests and metrics. */
    public int size() {
        return consumedUntilEpochSeconds.size();
    }

    private static String fingerprint(String ticket) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(ticket.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the JDK", e);
        }
    }
}
