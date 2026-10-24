package com.agenticai.core.agents.turn;

import java.time.Duration;
import java.util.PriorityQueue;
import java.util.concurrent.TimeUnit;
import reactor.core.Disposable;
import reactor.core.scheduler.Scheduler;

/**
 * A Reactor {@link Scheduler} whose clock only moves when a test says so: a delay of 90 seconds costs
 * no wall-clock time and cannot flake. {@code Mono.delay(d, scheduler)} and anything built on it run
 * against it; {@link #advance} runs every task that falls due, in order.
 */
final class ManualScheduler implements Scheduler {

    private record Task(long dueNanos, long seq, Runnable run, boolean[] cancelled) {}

    private final PriorityQueue<Task> queue =
            new PriorityQueue<>((a, b) -> a.dueNanos != b.dueNanos ? Long.compare(a.dueNanos, b.dueNanos) : Long.compare(a.seq, b.seq));
    private long nowNanos;
    private long seq;

    /** Virtual nanoseconds elapsed; the clock a {@code TurnToolActivity} under test runs on. */
    synchronized long nanos() {
        return nowNanos;
    }

    /** Moves time forward by {@code by}, running every task that becomes due on the way. */
    void advance(Duration by) {
        long target;
        synchronized (this) {
            target = nowNanos + by.toNanos();
        }
        while (true) {
            Task next;
            synchronized (this) {
                next = queue.peek();
                if (next == null || next.dueNanos > target) {
                    nowNanos = target;
                    return;
                }
                queue.poll();
                nowNanos = Math.max(nowNanos, next.dueNanos);
            }
            if (!next.cancelled[0]) {
                next.run.run();
            }
        }
    }

    @Override
    public Disposable schedule(Runnable task) {
        return schedule(task, 0, TimeUnit.NANOSECONDS);
    }

    @Override
    public synchronized Disposable schedule(Runnable task, long delay, TimeUnit unit) {
        boolean[] cancelled = new boolean[1];
        queue.add(new Task(nowNanos + unit.toNanos(delay), seq++, task, cancelled));
        return () -> cancelled[0] = true;
    }

    @Override
    public synchronized long now(TimeUnit unit) {
        return unit.convert(nowNanos, TimeUnit.NANOSECONDS);
    }

    @Override
    public Worker createWorker() {
        ManualScheduler self = this;
        return new Worker() {
            private volatile boolean disposed;

            @Override
            public Disposable schedule(Runnable task) {
                return self.schedule(task);
            }

            @Override
            public Disposable schedule(Runnable task, long delay, TimeUnit unit) {
                return self.schedule(task, delay, unit);
            }

            @Override
            public void dispose() {
                disposed = true;
            }

            @Override
            public boolean isDisposed() {
                return disposed;
            }
        };
    }
}
