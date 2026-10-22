package com.agenticai.core.resilience;

import com.agenticai.core.security.logging.LogSafe;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import org.slf4j.Logger;

/**
 * "Log this warning at most once per interval per key." For degraded paths that fire on every
 * request while a dependency is down (Redis unreachable, a breaker open): one WARN per minute tells
 * an operator everything the thousandth identical line would, and does not flood the log pipeline
 * during exactly the incident when it is most needed.
 *
 * <p>Thread-safe, allocation-light, bounded to {@value #MAX_KEYS} distinct keys (a full table
 * simply stops logging new keys until entries expire - it never grows without bound). Each emitted
 * line carries how many were suppressed since the previous one. The default time source is
 * monotonic ({@link System#nanoTime()}), so a wall-clock step cannot silence the log or let it
 * flood.
 */
public final class RateLimitedLog {

    static final int MAX_KEYS = 256;

    private final Logger log;
    private final long intervalMillis;
    private final LongSupplier nowMillis;
    private final Map<String, Slot> slots = new ConcurrentHashMap<>();

    private static final class Slot {
        private final AtomicLong lastLoggedAt;
        private final AtomicLong suppressed = new AtomicLong();

        private Slot(long lastLoggedAt) {
            this.lastLoggedAt = new AtomicLong(lastLoggedAt);
        }
    }

    public RateLimitedLog(Logger log, Duration interval) {
        this(
                log,
                interval,
                () -> java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime()));
    }

    public RateLimitedLog(Logger log, Duration interval, LongSupplier nowMillis) {
        this.log = log;
        this.intervalMillis = Math.max(1L, interval.toMillis());
        this.nowMillis = nowMillis;
    }

    /**
     * Logs {@code message} at WARN if {@code key} has not logged within the interval; otherwise
     * counts it as suppressed. {@code error} (nullable) is attached only to the line that is
     * emitted.
     *
     * @return true when a line was written.
     */
    public boolean warn(String key, String message, Throwable error) {
        long suppressed = admit(key);
        if (suppressed < 0) {
            return false;
        }
        emit(message, error, suppressed);
        return true;
    }

    public boolean warn(String key, String message) {
        return warn(key, message, null);
    }

    /**
     * Drop-in for {@code log.warn(format, args...)} at a degraded-path call site: same SLF4J
     * format, same trailing-Throwable convention, but at most one line per interval per call site.
     * The {@code format} string itself is the key, so each call site is limited independently and
     * the per-tenant / per-run arguments never multiply the keys.
     *
     * @return true when a line was written.
     */
    public boolean warnf(String format, Object... args) {
        long suppressed = admit(format);
        if (suppressed < 0) {
            return false;
        }
        log.warn(
                suppressed > 0
                        ? format
                                + " ("
                                + suppressed
                                + " similar event(s) suppressed since the last report)"
                        : format,
                sanitize(args));
        return true;
    }

    /**
     * Line breaks in an argument must not forge a second log line; a trailing Throwable keeps its
     * stack trace.
     */
    private static Object[] sanitize(Object[] args) {
        Object[] out = new Object[args.length];
        for (int i = 0; i < args.length; i++) {
            out[i] = args[i] instanceof Throwable ? args[i] : LogSafe.clean(args[i]);
        }
        return out;
    }

    /**
     * @return the number of events suppressed since the last emitted line when this one may be
     *     written, or -1 when it must be suppressed.
     */
    private long admit(String key) {
        long now = nowMillis.getAsLong();
        Slot slot = slots.get(key);
        if (slot == null) {
            if (slots.size() >= MAX_KEYS) {
                evictExpired(now);
                if (slots.size() >= MAX_KEYS) {
                    return -1L;
                }
            }
            if (slots.putIfAbsent(key, new Slot(now)) == null) {
                return 0L;
            }
            slot = slots.get(key);
            if (slot == null) {
                return -1L;
            }
        }
        long last = slot.lastLoggedAt.get();
        if (now - last >= intervalMillis && slot.lastLoggedAt.compareAndSet(last, now)) {
            return slot.suppressed.getAndSet(0L);
        }
        slot.suppressed.incrementAndGet();
        return -1L;
    }

    private void evictExpired(long now) {
        slots.entrySet().removeIf(e -> now - e.getValue().lastLoggedAt.get() >= intervalMillis);
    }

    private void emit(String message, Throwable error, long suppressedBefore) {
        String text =
                suppressedBefore > 0
                        ? message
                                + " ("
                                + suppressedBefore
                                + " similar event(s) suppressed since the last report)"
                        : message;
        if (error != null) {
            log.warn(text, error);
        } else {
            log.warn(text);
        }
    }
}
