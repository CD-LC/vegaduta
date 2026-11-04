package com.agenticai.core.chat;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * An in-process tally of the model calls (tokens, latency) made for each chat session, so an evaluation harness running
 * in the same JVM can read what one turn cost: {@link #peek} before and after, or {@link #drain} after each turn. It is
 * fed by the existing {@link LlmMetricsAdvisor} observation (no extra call, no network hop) and is OFF unless
 * {@code app.metrics.turn-usage.enabled=true}; while off, nothing is recorded and the advisor pays one null check.
 *
 * <p>One model call spans the whole tool loop of a turn (see {@link LlmMetricsAdvisor}), so {@code modelLatencyMs} is
 * model plus tool time; {@link Totals#wallMs()} is first call start to last call end. Tokens are as reported by the
 * provider ({@code null} reports count as 0, and {@code callsWithoutUsage} says how many). Bounded: past
 * {@link #MAX_SESSIONS} sessions the least recently used tally is dropped.
 */
public final class TurnUsageCounter {

    static final int MAX_SESSIONS = 5_000;

    private static volatile TurnUsageCounter active;

    /** What the sessions' calls added up to. */
    public record Totals(
            int calls,
            int failedCalls,
            int callsWithoutUsage,
            long promptTokens,
            long completionTokens,
            long totalTokens,
            long modelLatencyMs,
            long maxCallLatencyMs,
            Instant firstStartedAt,
            Instant lastFinishedAt) {

        static final Totals EMPTY = new Totals(0, 0, 0, 0, 0, 0, 0, 0, null, null);

        /** Wall-clock milliseconds from the first call's start to the last call's end; 0 with no calls. */
        public long wallMs() {
            return firstStartedAt == null || lastFinishedAt == null
                    ? 0
                    : Math.max(0, lastFinishedAt.toEpochMilli() - firstStartedAt.toEpochMilli());
        }
    }

    private final Map<String, Totals> bySession =
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Totals> eldest) {
                    return size() > MAX_SESSIONS;
                }
            };

    /** The installed counter, or null while the feature is off. */
    public static TurnUsageCounter active() {
        return active;
    }

    /** Installs (or, with null, removes) the process-wide counter; done by the configuration and by tests. */
    public static void install(TurnUsageCounter counter) {
        active = counter;
    }

    /** Adds one finished call to the session's tally; a call with no session id is not tallied. */
    public synchronized void record(
            String sessionId,
            Long promptTokens,
            Long completionTokens,
            Long totalTokens,
            boolean success,
            long latencyMs,
            Instant startedAt) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        Totals t = bySession.getOrDefault(sessionId, Totals.EMPTY);
        long p = promptTokens == null ? 0 : promptTokens;
        long c = completionTokens == null ? 0 : completionTokens;
        long total = totalTokens != null ? totalTokens : p + c;
        Instant first = t.firstStartedAt();
        if (startedAt != null && (first == null || startedAt.isBefore(first))) {
            first = startedAt;
        }
        Instant end = startedAt == null ? null : startedAt.plusMillis(latencyMs);
        Instant last = t.lastFinishedAt();
        if (end != null && (last == null || end.isAfter(last))) {
            last = end;
        }
        bySession.put(
                sessionId,
                new Totals(
                        t.calls() + 1,
                        t.failedCalls() + (success ? 0 : 1),
                        t.callsWithoutUsage() + (promptTokens == null && completionTokens == null ? 1 : 0),
                        t.promptTokens() + p,
                        t.completionTokens() + c,
                        t.totalTokens() + total,
                        t.modelLatencyMs() + latencyMs,
                        Math.max(t.maxCallLatencyMs(), latencyMs),
                        first,
                        last));
    }

    /** The session's tally so far (empty totals when none). */
    public synchronized Totals peek(String sessionId) {
        return bySession.getOrDefault(sessionId, Totals.EMPTY);
    }

    /** The session's tally, cleared: call it after a turn to get exactly that turn's usage. */
    public synchronized Totals drain(String sessionId) {
        Totals t = bySession.remove(sessionId);
        return t == null ? Totals.EMPTY : t;
    }

    synchronized int sessions() {
        return bySession.size();
    }
}
