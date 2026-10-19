package com.agenticai.core.quality.impl;

import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.concurrent.TenantAwareExecutors;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Hard limits for judge calls, shared by judge calibration and the eval judge.
 *
 * <p>Prod 2026-10-05: a calibration took 91 s and answered "0 of 20 rows judged" because the judge
 * (an obscure catalog-first model) answered every call with text no verdict parser could read. Each
 * call "succeeded" at the provider, so nothing ever threw, nothing stopped the loop, and the only
 * explanation was a statistics sentence about answer positions. Three things fix that:
 * <ul>
 *   <li>{@link #callWithTimeout}: every judge call has a hard wall-clock limit (a hung call used to
 *       hold the request thread for as long as the provider client allowed);</li>
 *   <li>{@link Tracker}: counts failed calls (error, timeout, unparseable output alike), remembers the
 *       FIRST reason and aborts the run after a few consecutive failures or when the overall budget
 *       is spent;</li>
 *   <li>{@link #describe}: a plain reason for a failure (HTTP status, timeout) rather than a stack
 *       trace or nothing.</li>
 * </ul>
 */
public final class JudgeCallGuard {

    public static final Duration DEFAULT_CALL_TIMEOUT = Duration.ofSeconds(20);
    public static final Duration DEFAULT_BUDGET = Duration.ofSeconds(120);
    /** Consecutive failures, with no verdict obtained yet, after which a run stops. */
    public static final int DEFAULT_LEADING_ABORT = 3;
    /** Consecutive failures, after at least one verdict, after which a run stops. */
    public static final int DEFAULT_CONSECUTIVE_ABORT = 6;

    private static final ExecutorService EXECUTOR =
            TenantAwareExecutors.newCachedThreadPool(
                    TenantAwareExecutors.numberedThreadFactory("judge-call-", true));

    private JudgeCallGuard() {
    }

    /** Why one judge call produced nothing. */
    public static class JudgeCallFailedException extends RuntimeException {
        public JudgeCallFailedException(String reason, Throwable cause) {
            super(reason, cause);
        }
    }

    /** Thrown by {@link Tracker} to stop a run; the message is the user-facing explanation. */
    public static class JudgeRunAbortedException extends RuntimeException {
        public JudgeRunAbortedException(String message) {
            super(message);
        }
    }

    /** {@code timeout} when it is a usable positive duration, else {@link #DEFAULT_CALL_TIMEOUT}. */
    public static Duration orDefault(Duration timeout) {
        return timeout == null || timeout.isZero() || timeout.isNegative() ? DEFAULT_CALL_TIMEOUT : timeout;
    }

    /**
     * Runs {@code call} on a worker thread (carrying this thread's tenant context) and waits at most
     * {@code timeout}. A timeout cancels the worker and throws {@link JudgeCallFailedException} with
     * "timed out after N s"; any other failure is rethrown as it came (RuntimeException) so callers
     * can still classify it.
     */
    public static String callWithTimeout(Callable<String> call, Duration timeout) {
        Duration limit = orDefault(timeout);
        Optional<TenantContext> tenant = TenantContext.current();
        Future<String> future = EXECUTOR.submit(() -> {
            tenant.ifPresent(TenantContext::set);
            try {
                return call.call();
            } finally {
                TenantContext.clear();
            }
        });
        try {
            return future.get(limit.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new JudgeCallFailedException("timed out after " + seconds(limit) + " s", e);
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new JudgeCallFailedException("interrupted while waiting for the judge", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            if (cause instanceof Error err) {
                throw err;
            }
            throw new JudgeCallFailedException(describe(cause), cause);
        }
    }

    private static String seconds(Duration d) {
        long ms = d.toMillis();
        return ms % 1000 == 0 ? Long.toString(ms / 1000) : String.format(java.util.Locale.ROOT, "%.1f", ms / 1000.0);
    }

    /** A short plain reason for a failure: HTTP status when the provider answered one, else the message. */
    public static String describe(Throwable e) {
        if (e == null) {
            return "the judge did not answer";
        }
        if (e instanceof JudgeCallFailedException) {
            return e.getMessage();
        }
        for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof RestClientResponseException http) {
                String statusText = http.getStatusText();
                return "HTTP " + http.getStatusCode().value()
                    + (statusText == null || statusText.isBlank() ? "" : " " + statusText.trim());
            }
        }
        String message = e.getMessage();
        return message != null && !message.isBlank() ? message.trim() : e.getClass().getSimpleName();
    }

    /** A completion, quoted for an error message: collapsed whitespace, capped. */
    public static String sample(String completion) {
        if (completion == null || completion.isBlank()) {
            return "empty completion";
        }
        String flat = completion.replaceAll("\\s+", " ").trim();
        return "unparseable output '" + (flat.length() > 80 ? flat.substring(0, 80) + "..." : flat) + "'";
    }

    /**
     * Per-run failure accounting. Not thread-safe by design: one run, one thread.
     * {@link #failure} / {@link #success} are called after every judge call;
     * {@link #checkBudget} before each call.
     */
    public static final class Tracker {
        private final String judgeModel;
        private final String unit;
        private final int totalCalls;
        private final long budgetNanos;
        private final int leadingAbort;
        private final int consecutiveAbort;
        private final long startedNanos = System.nanoTime();
        private int attempts;
        private int failures;
        private int successes;
        private int consecutive;
        private String firstReason;

        public Tracker(String judgeModel, String unit, int totalCalls, Duration budget,
                       int leadingAbort, int consecutiveAbort) {
            this.judgeModel = judgeModel;
            this.unit = unit;
            this.totalCalls = totalCalls;
            this.budgetNanos = (budget == null || budget.isNegative() || budget.isZero() ? DEFAULT_BUDGET : budget).toNanos();
            this.leadingAbort = Math.max(1, leadingAbort);
            this.consecutiveAbort = Math.max(this.leadingAbort, consecutiveAbort);
        }

        public Tracker(String judgeModel, String unit, int totalCalls) {
            this(judgeModel, unit, totalCalls, DEFAULT_BUDGET, DEFAULT_LEADING_ABORT, DEFAULT_CONSECUTIVE_ABORT);
        }

        /** Stops the run when the overall time budget is already spent. */
        public void checkBudget() {
            if (attempts > 0 && System.nanoTime() - startedNanos > budgetNanos) {
                throw new JudgeRunAbortedException(message("stopped after the " + seconds(Duration.ofNanos(budgetNanos))
                    + " s overall budget ran out", firstReason == null ? "too slow" : null));
            }
        }

        public void success() {
            attempts++;
            successes++;
            consecutive = 0;
        }

        /** Records one failed call; throws {@link JudgeRunAbortedException} when the run should stop. */
        public void failure(String reason) {
            attempts++;
            failures++;
            consecutive++;
            if (firstReason == null) {
                firstReason = reason == null || reason.isBlank() ? "the judge did not answer" : reason.trim();
            }
            int limit = successes == 0 ? leadingAbort : consecutiveAbort;
            if (consecutive >= limit) {
                throw new JudgeRunAbortedException(message("stopped after " + consecutive + " failures in a row", null));
            }
        }

        public int attempts() {
            return attempts;
        }

        public int failures() {
            return failures;
        }

        public int successes() {
            return successes;
        }

        public String firstReason() {
            return firstReason;
        }

        /** The error for a run that ended with nothing usable. */
        public JudgeRunAbortedException noUsableVerdicts() {
            return new JudgeRunAbortedException(message(null, null));
        }

        /**
         * 'Judge model X returned no usable verdicts (N of M judge calls failed[, note]: first reason)'.
         * N is what failed so far, M what was attempted so far.
         */
        private String message(String note, String fallbackReason) {
            String reason = firstReason != null ? firstReason
                : fallbackReason != null ? fallbackReason : "no call returned a usable answer";
            String model = judgeModel == null || judgeModel.isBlank() ? "" : " " + judgeModel;
            String reasonText = reason.endsWith(".") ? reason.substring(0, reason.length() - 1) : reason;
            return "Judge model" + model + " returned no usable verdicts (" + failures + " of " + Math.max(attempts, failures)
                + " " + unit + " failed" + (note == null ? "" : ", " + note) + ": " + reasonText + "). "
                + "Nothing was recorded. Check the judge provider in Providers settings or pick another judge model.";
        }

        public int totalCalls() {
            return totalCalls;
        }
    }
}
