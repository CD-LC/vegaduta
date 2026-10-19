package com.agenticai.core.platform.reliability;

import com.agenticai.core.platform.reliability.ReliabilityApi.DependencyEntry;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpTimeoutException;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Runs dependency probes in parallel, each under a hard timeout. Pure
 * mechanics, no knowledge of any dependency: it turns "the body returned",
 * "the body threw" and "the body did not answer in time" into
 * {@link DependencyEntry} rows, so one hung dependency can never stall the
 * response past the timeout and never take its siblings down with it.
 *
 * <p>Probe bodies run on the supplied executor (production: virtual threads),
 * never on the caller's thread; a timed-out body is cancelled (interrupted)
 * and its thread is left to finish on its own.
 */
final class ProbeRunner {

    /** What a probe body concluded. {@code detail} must contain no secret, hostname or URL. */
    record Outcome(DependencyStatus status, String detail) {
    }

    @FunctionalInterface
    interface Body {
        Outcome check() throws Exception;
    }

    record Spec(String name, String category, Body body) {
    }

    private final ExecutorService executor;
    private final Duration timeout;
    private final Clock clock;

    ProbeRunner(ExecutorService executor, Duration timeout, Clock clock) {
        this.executor = executor;
        this.timeout = timeout;
        this.clock = clock;
    }

    Duration timeout() {
        return timeout;
    }

    /** Runs one probe, blocking the caller for at most the timeout. Never throws. */
    DependencyEntry run(Spec spec) {
        long startNanos = System.nanoTime();
        Callable<Outcome> task = spec.body()::check;
        Future<Outcome> future = executor.submit(task);
        try {
            Outcome outcome = future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (outcome == null) {
                return entry(spec, DependencyStatus.UNKNOWN, null, "the check returned no result");
            }
            return entry(spec, outcome.status(), elapsedMs(startNanos), outcome.detail());
        } catch (TimeoutException e) {
            future.cancel(true);
            return entry(spec, DependencyStatus.DOWN, timeout.toMillis(),
                "no answer within " + timeout.toMillis() + " ms");
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            return entry(spec, DependencyStatus.UNKNOWN, null, "the check was interrupted");
        } catch (ExecutionException e) {
            return entry(spec, DependencyStatus.DOWN, elapsedMs(startNanos), "check failed: " + describe(e.getCause()));
        } catch (RuntimeException e) {
            return entry(spec, DependencyStatus.UNKNOWN, null, "the check could not run: " + describe(e));
        }
    }

    /** Runs every probe in parallel and returns their rows in the order given. Never throws. */
    List<DependencyEntry> runAll(List<Spec> specs) {
        List<CompletableFuture<DependencyEntry>> futures = specs.stream()
            .map(spec -> CompletableFuture.supplyAsync(() -> run(spec), executor))
            .toList();
        return futures.stream().map(CompletableFuture::join).toList();
    }

    /**
     * Runs a helper (e.g. a database lookup that decides which probes to run)
     * under the same timeout. Throws on timeout or failure so the caller can
     * degrade explicitly.
     */
    <T> T call(Callable<T> body) throws Exception {
        Future<T> future = executor.submit(body);
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw e;
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception ex) {
                throw ex;
            }
            throw e;
        }
    }

    private DependencyEntry entry(Spec spec, DependencyStatus status, Long latencyMs, String detail) {
        return new DependencyEntry(spec.name(), spec.category(), status, latencyMs, clock.instant(), detail);
    }

    private static long elapsedMs(long startNanos) {
        return Math.max(0L, (System.nanoTime() - startNanos) / 1_000_000L);
    }

    /**
     * A short, secret-free description of a failure: the exception CLASS only.
     * Messages are never copied - JDBC, Redis and HTTP client messages routinely
     * contain hostnames, ports or connection strings.
     */
    static String describe(Throwable t) {
        if (t == null) {
            return "unknown error";
        }
        if (t instanceof ConnectException) {
            return "connection refused";
        }
        if (t instanceof SocketTimeoutException || t instanceof HttpTimeoutException) {
            return "timed out";
        }
        if (t instanceof UnknownHostException) {
            return "name not resolved";
        }
        if (t instanceof InterruptedException) {
            return "interrupted";
        }
        return t.getClass().getSimpleName();
    }
}
