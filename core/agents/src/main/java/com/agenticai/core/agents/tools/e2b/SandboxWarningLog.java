package com.agenticai.core.agents.tools.e2b;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The bounded per-session "why was this file not mounted" log of {@link SandboxFileRegistry},
 * surfaced in the next {@code run_code} result. Not thread-safe on its own: every call is made while
 * holding the registry's monitor, exactly as the fields were guarded before the split.
 */
final class SandboxWarningLog {

    private static final int MAX_WARNING_KEYS = 200;
    private static final int MAX_WARNINGS_PER_SESSION = 10;

    /** Why a file was not mounted, surfaced in the next run_code result. */
    private final Map<String, List<String>> warnings = new LinkedHashMap<>();

    private final Map<String, Long> warningTouched = new LinkedHashMap<>();

    void add(String key, long now, String message) {
        if (!warnings.containsKey(key) && warnings.size() >= MAX_WARNING_KEYS) {
            String oldest = warnings.keySet().iterator().next();
            warnings.remove(oldest);
            warningTouched.remove(oldest);
        }
        List<String> list = warnings.computeIfAbsent(key, k -> new ArrayList<>());
        if (list.size() < MAX_WARNINGS_PER_SESSION && !list.contains(message)) {
            list.add(message);
        }
        warningTouched.put(key, now);
    }

    /** Returns and clears the warnings of {@code key} (empty when none). */
    List<String> drain(String key) {
        warningTouched.remove(key);
        List<String> list = warnings.remove(key);
        return list == null ? List.of() : List.copyOf(list);
    }

    /** Drops the warnings of keys untouched for longer than {@code ttlMillis}. */
    void evictExpired(long now, long ttlMillis) {
        warningTouched.values().removeIf(t -> now - t > ttlMillis);
        warnings.keySet().retainAll(warningTouched.keySet());
    }
}
