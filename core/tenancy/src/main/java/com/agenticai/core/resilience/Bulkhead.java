package com.agenticai.core.resilience;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A concurrency cap for one dependency: at most {@code maxConcurrent} calls in
 * flight, so a slow dependency cannot absorb every virtual thread / connection
 * the process has. Callers over the cap are shed immediately (or after a short
 * {@code maxWait}) instead of queueing behind a stuck call.
 *
 * <p>Semaphore based, no I/O, safe on a request thread. Metrics:
 * gauge {@code resilience.bulkhead.available{name}} and counter
 * {@code resilience.bulkhead.rejected{name}}.
 */
public final class Bulkhead {

    private final String name;
    private final int maxConcurrent;
    private final long maxWaitNanos;
    private final Semaphore semaphore;
    private final Counter rejectedCounter;

    public Bulkhead(String name, int maxConcurrent, Duration maxWait, MeterRegistry meters) {
        this.name = name;
        this.maxConcurrent = Math.max(1, maxConcurrent);
        this.maxWaitNanos = maxWait == null || maxWait.isNegative() ? 0L : maxWait.toNanos();
        this.semaphore = new Semaphore(this.maxConcurrent);
        if (meters != null) {
            Gauge.builder("resilience.bulkhead.available", semaphore, Semaphore::availablePermits)
                .tag("name", name).register(meters);
            this.rejectedCounter = Counter.builder("resilience.bulkhead.rejected").tag("name", name).register(meters);
        } else {
            this.rejectedCounter = null;
        }
    }

    public String name() {
        return name;
    }

    public int maxConcurrent() {
        return maxConcurrent;
    }

    public int available() {
        return semaphore.availablePermits();
    }

    /**
     * @return a ticket to close when the call ends, or {@code null} when the bulkhead is full
     *     (after waiting up to the configured {@code maxWait}). An interrupted wait counts as full
     *     and re-asserts the interrupt flag.
     */
    public Ticket tryAcquire() {
        boolean acquired;
        try {
            acquired = maxWaitNanos == 0L
                ? semaphore.tryAcquire()
                : semaphore.tryAcquire(maxWaitNanos, TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            acquired = false;
        }
        if (!acquired) {
            if (rejectedCounter != null) {
                rejectedCounter.increment();
            }
            return null;
        }
        return new Ticket(semaphore);
    }

    /** Releases one slot, once, however many times {@link #close()} is called. */
    public static final class Ticket implements AutoCloseable {

        private final Semaphore semaphore;
        private final AtomicBoolean released = new AtomicBoolean(false);

        private Ticket(Semaphore semaphore) {
            this.semaphore = semaphore;
        }

        @Override
        public void close() {
            if (released.compareAndSet(false, true)) {
                semaphore.release();
            }
        }
    }
}
