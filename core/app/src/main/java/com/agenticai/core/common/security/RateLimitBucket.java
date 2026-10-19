package com.agenticai.core.common.security;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Token bucket rate limiter: tracks request count within a time window.
 * Used to detect high-velocity API calls from a single IP or user.
 */
public class RateLimitBucket {
    private final AtomicInteger requestCount;
    private final Duration window;
    private final Instant createdAt;
    private Instant lastResetAt;

    public RateLimitBucket(Duration window) {
        this.requestCount = new AtomicInteger(0);
        this.window = window;
        this.createdAt = Instant.now();
        this.lastResetAt = createdAt;
    }

    /**
     * Record a single request and return the new count.
     * Resets the bucket if the window has expired.
     */
    public synchronized int recordRequest() {
        if (isExpired()) {
            reset();
        }
        return requestCount.incrementAndGet();
    }

    /**
     * Get the current request count in this bucket.
     */
    public int getCurrentCount() {
        if (isExpired()) {
            return 0;
        }
        return requestCount.get();
    }

    /**
     * Get the current request rate (requests per minute).
     */
    public double getCurrentRate() {
        int count = getCurrentCount();
        if (count == 0) {
            return 0.0;
        }

        long elapsedMillis = System.currentTimeMillis() - lastResetAt.toEpochMilli();
        double elapsedMinutes = elapsedMillis / 60_000.0;

        return elapsedMinutes > 0 ? count / elapsedMinutes : count;
    }

    /**
     * Check if the bucket is expired (window has passed).
     */
    private boolean isExpired() {
        return Instant.now().isAfter(lastResetAt.plus(window));
    }

    /**
     * Reset the bucket for the next window.
     */
    private void reset() {
        requestCount.set(0);
        lastResetAt = Instant.now();
    }

    /**
     * Get time remaining in the current window (in seconds).
     */
    public long getTimeRemainingSeconds() {
        Instant windowEnd = lastResetAt.plus(window);
        Instant now = Instant.now();

        if (now.isAfter(windowEnd)) {
            return 0;
        }

        return Duration.between(now, windowEnd).getSeconds();
    }

    @Override
    public String toString() {
        return "RateLimitBucket{" +
                "count=" + getCurrentCount() +
                ", rate=" + String.format("%.2f", getCurrentRate()) + " req/min" +
                ", window=" + window +
                ", remaining=" + getTimeRemainingSeconds() + "s" +
                '}';
    }
}
