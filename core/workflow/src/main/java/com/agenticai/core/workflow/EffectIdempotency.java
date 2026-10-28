package com.agenticai.core.workflow;

/**
 * Thread-bound idempotency key of the irreversible node about to run, set by {@link
 * WorkflowEffectJournal} for the duration of the node's side effect so a handler that calls an
 * external API can forward it (the HTTP node sends it as {@code Idempotency-Key}). A ThreadLocal
 * rather than a {@link NodeInvocation} field for the same reason {@link ExpressionScopeContext} is
 * one: {@code NodeInvocation} is constructed by many tests and callers. Empty when the journal is
 * disabled or the node is not journaled, in which case handlers behave exactly as before.
 */
public final class EffectIdempotency {

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private EffectIdempotency() {}

    static void bind(String key) {
        CURRENT.set(key);
    }

    static void clear() {
        CURRENT.remove();
    }

    /** The key for the node executing on this thread, or null. */
    public static String current() {
        return CURRENT.get();
    }

    /**
     * Stable string {@code runId:nodeId:iteration}; the same for every attempt of the same
     * (run, node, iteration). Characters outside {@code [A-Za-z0-9._:-]} become {@code _} so the
     * value is safe in an HTTP header.
     */
    public static String keyFor(String runId, String nodeId, String iteration) {
        return sanitize(runId) + ":" + sanitize(nodeId) + ":" + sanitize(iteration);
    }

    private static String sanitize(String part) {
        return part == null ? "" : part.replaceAll("[^A-Za-z0-9._:-]", "_");
    }
}
