package com.agenticai.core.agents.tools.x402;

import java.time.Clock;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Sliding-window rate limiter for the public {@code /x402/*} surface. The endpoint is deliberately
 * unauthenticated (the payment IS the credential), which makes it reachable by anonymous scrapers
 * and a tempting place to burn Ed25519-verification CPU or probe slugs - this bounds requests per
 * client IP.
 *
 * <p>In-memory and per-replica like {@code A2aTaskStore}'s limits: exactness across replicas is not
 * the goal; keeping any single client from monopolizing a pod is. Config: {@code
 * app.x402.public-rate-limit-per-minute} (0 disables).
 */
@Component
public class X402RateLimiter {

    private static final long WINDOW_MILLIS = 60_000;
    private static final int MAX_KEYS = 50_000;

    private final int perMinute;
    private final Clock clock;
    private final Map<String, Deque<Long>> hits = new ConcurrentHashMap<>();
    private volatile long lastSweep = 0;

    @Autowired
    public X402RateLimiter(@Value("${app.x402.public-rate-limit-per-minute:60}") int perMinute) {
        this(perMinute, Clock.systemUTC());
    }

    X402RateLimiter(int perMinute, Clock clock) {
        this.perMinute = perMinute;
        this.clock = clock;
    }

    /** True when the caller is within budget; a hit is recorded either way. */
    public boolean tryAcquire(String clientKey) {
        if (perMinute <= 0) {
            return true;
        }
        long now = clock.millis();
        long cutoff = now - WINDOW_MILLIS;
        maybeSweep(now, cutoff);
        Deque<Long> deque = hits.computeIfAbsent(clientKey, k -> new ArrayDeque<>());
        synchronized (deque) {
            while (!deque.isEmpty() && deque.peekFirst() < cutoff) {
                deque.pollFirst();
            }
            deque.addLast(now);
            return deque.size() <= perMinute;
        }
    }

    public int getPerMinute() {
        return perMinute;
    }

    /** Periodic dead-key GC so one-off IPs cannot grow the map unboundedly. */
    private void maybeSweep(long now, long cutoff) {
        if (hits.size() < MAX_KEYS || now - lastSweep < WINDOW_MILLIS) {
            return;
        }
        lastSweep = now;
        hits.entrySet()
                .removeIf(
                        e -> {
                            Deque<Long> deque = e.getValue();
                            synchronized (deque) {
                                while (!deque.isEmpty() && deque.peekFirst() < cutoff) {
                                    deque.pollFirst();
                                }
                                return deque.isEmpty();
                            }
                        });
    }
}
