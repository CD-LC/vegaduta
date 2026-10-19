package com.agenticai.core.brief;

import java.util.LinkedHashMap;
import java.util.Map;

/** Assigns refs to skeleton keys and resolves cross-references; the only source of refs in a planned brief. */
final class PlannerRefs {
    private final Map<Character, Map<String, String>> byKind = new LinkedHashMap<>();
    private final Map<Character, Integer> counters = new LinkedHashMap<>();

    /** Registers a key (null/blank/duplicate keys still consume a ref so ordering stays stable). */
    String register(char kind, String key) {
        int n = counters.merge(kind, 1, Integer::sum);
        String ref = String.valueOf(kind) + n;
        if (key != null) {
            byKind.computeIfAbsent(kind, k -> new LinkedHashMap<>()).putIfAbsent(key, ref);
        }
        return ref;
    }

    /** Binds a key to a ref chosen elsewhere (MCP, channels: refs depend on what survived). */
    void bind(char kind, String key, String ref) {
        if (key != null) {
            byKind.computeIfAbsent(kind, k -> new LinkedHashMap<>()).put(key, ref);
        }
    }

    void forget(char kind, String key) {
        Map<String, String> m = byKind.get(kind);
        if (m != null && key != null) {
            m.remove(key);
        }
    }

    String lookup(char kind, String key) {
        Map<String, String> m = byKind.get(kind);
        return m == null || key == null ? null : m.get(key);
    }

    /** Ref for a key registered earlier; positional fallback keeps a keyless entry addressable. */
    String ref(char kind, String key) {
        String r = lookup(kind, key);
        if (r != null) {
            return r;
        }
        int n = counters.getOrDefault(kind, 0) + 1;
        counters.put(kind, n);
        return String.valueOf(kind) + n;
    }
}
