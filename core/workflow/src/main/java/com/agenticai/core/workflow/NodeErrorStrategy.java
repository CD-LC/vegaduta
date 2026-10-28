package com.agenticai.core.workflow;

import com.agenticai.core.security.TenantLimitExceededException;
import com.agenticai.core.workflow.dto.NodeTraceEntry;
import com.agenticai.core.workflow.dto.WorkflowNodeDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Per-node error strategy ({@code onError} / {@code retry} in a node's config JSON - no schema
 * change). Pure helpers owned by {@link WorkflowExecutionEngine}; everything here is a no-op unless
 * the engine's {@code app.workflow.node-error-strategy.enabled} flag is on, in which case {@link
 * #parse} is the only entry point the engine calls.
 *
 * <p>Config shape (all optional; absent = exactly today's behaviour):
 *
 * <pre>
 * "onError": "fail" | "continue" | "branch"
 * "retry":   { "maxAttempts": 1..5, "backoffMs": 0..60000, "backoff": "fixed"|"exponential",
 *              "retryOn": "all"|"transient" }
 * "retryUnsafe": true   // opt-in: allow retrying an irreversible side-effect node
 * </pre>
 *
 * <ul>
 *   <li>{@code fail} (default) - today's behaviour exactly, including the pre-existing implicit
 *       routing of a failure down an outgoing {@code error} edge when one is wired.
 *   <li>{@code continue} - the failure does not stop the run: the node's output becomes {@code
 *       {"error": ..., "nodeId": ...}} and the run continues down the node's normal outgoing edge.
 *   <li>{@code branch} - route to the node's outgoing {@code error} branch when one is wired, else
 *       fail.
 * </ul>
 *
 * <p>Retry wraps the node execution with a bounded number of TOTAL attempts. A node whose type has
 * an irreversible side effect (an HTTP call that is not GET/HEAD/OPTIONS, bulk email, tool, device
 * control, CRM lead, media, output, sub-workflow) is never retried unless it sets {@code
 * retryUnsafe=true}: a duplicated send is worse than a failed one. The same irreversible list also
 * gates the legacy retry path - {@link RetryPolicy#isRetryableNode} delegates to {@link
 * #isIrreversible} (with the same {@code retryUnsafe} opt-in), so an un-configured node can no
 * longer be blind-retried into a duplicate side effect.
 *
 * <p>The retry/backoff idea follows the n8n "Retry on fail / On error" settings and Dify's per-node
 * error strategy (continue vs. fail-branch); no code from either is used.
 */
final class NodeErrorStrategy {

    /** What to do once a node has failed for good. */
    enum OnError {
        FAIL,
        CONTINUE,
        BRANCH
    }

    /**
     * @param requestedAttempts what the author asked for (clamped to 1..5)
     * @param maxAttempts the total attempts actually allowed - 1 when the irreversible guard
     *     blocked a retry
     * @param blockedByGuard true when the author asked for more than one attempt but the node type
     *     forbids it
     */
    record Retry(
            int requestedAttempts,
            int maxAttempts,
            long backoffMs,
            boolean exponential,
            boolean transientOnly,
            boolean blockedByGuard) {}

    /** {@code retry} is null when the node configured no retry block. */
    record Policy(OnError onError, Retry retry) {}

    /** One failed attempt that was followed by another one. */
    record Attempt(
            int number,
            int maxAttempts,
            String error,
            Instant startedAt,
            Instant failedAt,
            long delayMs) {}

    @FunctionalInterface
    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    static final int MAX_ATTEMPTS_CEILING = 5;
    static final int DEFAULT_ATTEMPTS = 3;
    static final long DEFAULT_BACKOFF_MS = 1_000L;
    static final long MAX_BACKOFF_MS = 60_000L;

    /**
     * Node types whose execution has an irreversible external effect. {@code http} is handled
     * separately (only non-GET/HEAD/OPTIONS methods count). {@code output} and {@code workflow} are
     * not in the brief's list but are the same hazard (a message send / a whole sub-run), so they
     * are guarded too.
     */
    private static final Set<String> IRREVERSIBLE_TYPES =
            Set.of(
                    "bulk_email",
                    "tool",
                    "device_control",
                    "crm_lead",
                    "media",
                    "output",
                    "workflow");

    private static final Set<String> SAFE_HTTP_METHODS = Set.of("GET", "HEAD", "OPTIONS");

    private static final Pattern TRANSIENT_STATUS = Pattern.compile("\\b(408|429|502|503|504)\\b");
    private static final String[] TRANSIENT_NAME_PARTS = {
        "timeout",
        "timedout",
        "connectexception",
        "unknownhost",
        "resourceaccess",
        "ratelimit",
        "toomanyrequests",
        "serviceunavailable",
        "badgateway",
        "gatewaytimeout",
        "transient",
        "retryable"
    };
    private static final String[] TRANSIENT_MESSAGE_PARTS = {
        "timed out", "timeout", "connection reset", "connection refused", "temporarily unavailable",
                "rate limit",
        "too many requests", "overloaded", "service unavailable", "bad gateway", "gateway timeout",
                "try again"
    };

    private NodeErrorStrategy() {}

    /**
     * The node's strategy, or null when it configures nothing that changes behaviour ({@code
     * onError} absent or {@code fail}, and no {@code retry} block) - the engine then takes today's
     * code path unchanged.
     */
    static Policy parse(WorkflowNodeDto node) {
        Map<String, Object> config = node.config();
        OnError onError = parseOnError(config.get("onError"));
        Retry retry = parseRetry(node, config);
        if (onError == OnError.FAIL && retry == null) {
            return null;
        }
        return new Policy(onError, retry);
    }

    static OnError parseOnError(Object raw) {
        if (raw == null) {
            return OnError.FAIL;
        }
        return switch (raw.toString().trim().toLowerCase(Locale.ROOT)) {
            case "continue" -> OnError.CONTINUE;
            case "branch" -> OnError.BRANCH;
            default -> OnError.FAIL;
        };
    }

    private static Retry parseRetry(WorkflowNodeDto node, Map<String, Object> config) {
        if (!(config.get("retry") instanceof Map<?, ?> raw)) {
            return null;
        }
        int requested =
                clamp(intOr(raw.get("maxAttempts"), DEFAULT_ATTEMPTS), 1, MAX_ATTEMPTS_CEILING);
        long backoff = clamp(longOr(raw.get("backoffMs"), DEFAULT_BACKOFF_MS), 0L, MAX_BACKOFF_MS);
        boolean exponential = "exponential".equalsIgnoreCase(str(raw.get("backoff")));
        boolean transientOnly = "transient".equalsIgnoreCase(str(raw.get("retryOn")));
        boolean unsafeAllowed = truthy(config.get("retryUnsafe")) || truthy(raw.get("retryUnsafe"));
        boolean blocked = requested > 1 && isIrreversible(node) && !unsafeAllowed;
        return new Retry(
                requested, blocked ? 1 : requested, backoff, exponential, transientOnly, blocked);
    }

    /** True for a node whose re-execution could duplicate an external side effect. */
    static boolean isIrreversible(WorkflowNodeDto node) {
        String type = node.type() == null ? "" : node.type().toLowerCase(Locale.ROOT);
        if (IRREVERSIBLE_TYPES.contains(type)) {
            return true;
        }
        if ("http".equals(type)) {
            String method = str(node.config().get("method"));
            return !SAFE_HTTP_METHODS.contains(
                    method.isEmpty() ? "GET" : method.toUpperCase(Locale.ROOT));
        }
        return false;
    }

    /**
     * Delay after failed attempt {@code failedAttempt} (1-based): fixed, or doubled per attempt,
     * capped.
     */
    static long delayMillis(Retry retry, int failedAttempt) {
        if (!retry.exponential()) {
            return retry.backoffMs();
        }
        int exponent = Math.min(Math.max(failedAttempt, 1) - 1, 20);
        return Math.min(retry.backoffMs() << exponent, MAX_BACKOFF_MS);
    }

    /**
     * Whether {@code failure} may be retried under {@code retry}: never a quota rejection;
     * transient-only filters.
     */
    static boolean shouldRetry(Retry retry, RuntimeException failure) {
        if (failure instanceof TenantLimitExceededException
            || failure instanceof NodeAttemptExecutor.NodeStepTimeoutException) {
            return false;
        }
        return !retry.transientOnly() || isTransient(failure);
    }

    /**
     * Best-effort classification of a failure as a transient (timeout / overload / network) one.
     */
    static boolean isTransient(Throwable failure) {
        Throwable current = failure;
        for (int depth = 0; current != null && depth < 8; depth++, current = current.getCause()) {
            if (current instanceof java.io.IOException
                    || current instanceof java.io.UncheckedIOException
                    || current instanceof java.util.concurrent.TimeoutException
                    || current instanceof java.util.concurrent.RejectedExecutionException) {
                return true;
            }
            String name = current.getClass().getSimpleName().toLowerCase(Locale.ROOT);
            for (String part : TRANSIENT_NAME_PARTS) {
                if (name.contains(part)) {
                    return true;
                }
            }
            String message =
                    current.getMessage() == null
                            ? ""
                            : current.getMessage().toLowerCase(Locale.ROOT);
            for (String part : TRANSIENT_MESSAGE_PARTS) {
                if (message.contains(part)) {
                    return true;
                }
            }
            if (TRANSIENT_STATUS.matcher(message).find()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Runs {@code attempt} up to {@code retry.maxAttempts()} times. Every failed attempt that is
     * followed by another one is appended to {@code failedOut} (the caller turns them into trace
     * entries); the final failure is rethrown unchanged. {@code attempt} must perform exactly ONE
     * try (no inner retry loop).
     */
    static <T> T runWithRetry(
            Retry retry,
            Supplier<T> attempt,
            Sleeper sleeper,
            Supplier<Instant> clock,
            List<Attempt> failedOut) {
        int number = 0;
        while (true) {
            number++;
            Instant started = clock.get();
            try {
                return attempt.get();
            } catch (RuntimeException failure) {
                if (number >= retry.maxAttempts() || !shouldRetry(retry, failure)) {
                    throw failure;
                }
                long delay = delayMillis(retry, number);
                failedOut.add(
                        new Attempt(
                                number,
                                retry.maxAttempts(),
                                message(failure),
                                started,
                                clock.get(),
                                delay));
                if (delay > 0) {
                    try {
                        sleeper.sleep(delay);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw failure;
                    }
                }
            }
        }
    }

    /**
     * Trace entries for the retried attempts (one {@code ERROR} row each, so every attempt shows in
     * the run steps), plus - when the run failed for good and the irreversible guard stopped the
     * retry the author asked for - one row saying so.
     */
    static List<NodeTraceEntry> attemptEntries(
            WorkflowNodeDto node, List<Attempt> failed, Retry retry, boolean failedForGood) {
        List<NodeTraceEntry> entries = new ArrayList<>();
        for (Attempt a : failed) {
            entries.add(
                    new NodeTraceEntry(
                            node.id(),
                            node.type(),
                            node.label() + " (attempt " + a.number() + "/" + a.maxAttempts() + ")",
                            "ERROR",
                            "Attempt "
                                    + a.number()
                                    + "/"
                                    + a.maxAttempts()
                                    + " failed: "
                                    + a.error()
                                    + (a.delayMs() > 0
                                            ? " - retrying in " + a.delayMs() + " ms."
                                            : " - retrying."),
                            a.startedAt(),
                            a.failedAt(),
                            List.of(a.error())));
        }
        if (failedForGood && retry != null && retry.blockedByGuard()) {
            Instant now = Instant.now();
            entries.add(
                    new NodeTraceEntry(
                            node.id(),
                            node.type(),
                            node.label(),
                            "ERROR",
                            "Not retried: " + guardNote(node, retry),
                            now,
                            now,
                            List.of(guardNote(node, retry))));
        }
        return entries;
    }

    static String guardNote(WorkflowNodeDto node, Retry retry) {
        return "node type '"
                + node.type()
                + "' has irreversible side effects, so "
                + retry.requestedAttempts()
                + " attempts were reduced to 1 (set retryUnsafe=true on the node to allow retrying it).";
    }

    /**
     * The output of a node whose failure was swallowed by {@code onError: continue}: {@code {error,
     * nodeId}}.
     */
    static String continueOutput(
            ObjectMapper objectMapper, WorkflowNodeDto node, String errorMessage) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put(
                "error",
                errorMessage == null || errorMessage.isBlank() ? "Node failed." : errorMessage);
        out.put("nodeId", node.id());
        try {
            return objectMapper.writeValueAsString(out);
        } catch (Exception e) {
            return "{\"error\":\"Node failed.\",\"nodeId\":\"" + node.id() + "\"}";
        }
    }

    private static String message(Throwable t) {
        String m = t.getMessage();
        return m == null || m.isBlank() ? t.getClass().getSimpleName() : m;
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString().trim();
    }

    private static boolean truthy(Object o) {
        return o instanceof Boolean b
                ? b
                : o != null && "true".equalsIgnoreCase(o.toString().trim());
    }

    private static int intOr(Object raw, int fallback) {
        return (int)
                Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, longOr(raw, fallback)));
    }

    private static long longOr(Object raw, long fallback) {
        if (raw == null) {
            return fallback;
        }
        try {
            return raw instanceof Number n ? n.longValue() : Long.parseLong(raw.toString().trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static long clamp(long v, long lo, long hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
