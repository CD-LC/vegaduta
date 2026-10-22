package com.agenticai.core.agents.tools.mcpapps;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import tools.jackson.databind.JsonNode;

/**
 * Per-request guards of {@link McpAppsService}: the sliding-window rate limit applied to app views
 * and app-issued tools/call, and the recursive {@code ui://} mention check on tool arguments. Holds
 * only the in-memory rate-limit windows; split out of the service only to keep each file
 * reviewable.
 */
final class McpAppsRequestGuard {

    private static final int MAX_JSON_DEPTH = 12;

    private final Map<String, Deque<Long>> callWindows = new ConcurrentHashMap<>();

    static boolean mentionsUiUri(JsonNode n, int depth) {
        if (depth > MAX_JSON_DEPTH) {
            return true; // pathological nesting is refused rather than walked
        }
        if (n.isString()) {
            return n.asString().toLowerCase(Locale.ROOT).contains("ui://");
        }
        if (n.isObject()) {
            var it = n.properties().iterator();
            while (it.hasNext()) {
                var f = it.next();
                if (f.getKey().toLowerCase(Locale.ROOT).contains("ui://")
                        || mentionsUiUri(f.getValue(), depth + 1)) {
                    return true;
                }
            }
        } else if (n.isArray()) {
            for (JsonNode c : n) {
                if (mentionsUiUri(c, depth + 1)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Sliding one-minute window per key; throws RATE_LIMITED when {@code perMinute} is reached. */
    void rateLimit(String key, int perMinute) {
        long now = System.currentTimeMillis();
        Deque<Long> w = callWindows.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (w) {
            while (!w.isEmpty() && now - w.peekFirst() > 60_000L) {
                w.pollFirst();
            }
            if (w.size() >= perMinute) {
                throw new McpAppsException(
                        McpAppsException.Code.RATE_LIMITED,
                        "Too many requests from this app. Slow down.");
            }
            w.addLast(now);
        }
        if (callWindows.size() > 5000) {
            callWindows
                    .entrySet()
                    .removeIf(
                            e -> {
                                synchronized (e.getValue()) {
                                    return e.getValue().isEmpty()
                                            || now - e.getValue().peekLast() > 60_000L;
                                }
                            });
        }
    }
}
