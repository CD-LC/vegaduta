package com.agenticai.core.quality.trajectory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/**
 * One step of an expected trajectory: a tool name and, optionally, matchers for some of its
 * arguments. A step with no matchers accepts any arguments. Argument keys are dotted object paths
 * ({@code opts.limit}); only the listed arguments are checked - extra arguments on the actual call
 * are fine.
 */
final class ExpectedStep {

    private final String tool;
    private final Map<String, ArgMatcher> matchers;
    private final Map<String, JsonNode> rawMatchers;

    ExpectedStep(String tool, Map<String, ArgMatcher> matchers, Map<String, JsonNode> rawMatchers) {
        this.tool = tool;
        this.matchers = new LinkedHashMap<>(matchers);
        this.rawMatchers = new LinkedHashMap<>(rawMatchers);
    }

    String tool() {
        return tool;
    }

    /** The matchers as written in the spec, for canonical re-serialisation. */
    Map<String, JsonNode> rawMatchers() {
        return rawMatchers;
    }

    boolean hasMatchers() {
        return !matchers.isEmpty();
    }

    /** True when the actual call is this tool and every listed argument matcher passes. */
    boolean matches(TrajectoryToolCall actual) {
        return tool.equals(actual.tool()) && mismatchedArgs(actual.args()).isEmpty();
    }

    /**
     * Names of the listed arguments whose matcher failed on this call's arguments (empty = all
     * pass).
     */
    List<String> mismatchedArgs(JsonNode actualArgs) {
        List<String> failed = new ArrayList<>();
        for (Map.Entry<String, ArgMatcher> e : matchers.entrySet()) {
            if (!e.getValue().matches(valueAt(actualArgs, e.getKey()))) {
                failed.add(e.getKey());
            }
        }
        return failed;
    }

    /**
     * Walks a dotted object path; null when any segment is missing or the container is not an
     * object.
     */
    static JsonNode valueAt(JsonNode root, String dottedPath) {
        JsonNode node = root;
        int start = 0;
        while (node != null && start <= dottedPath.length()) {
            int dot = dottedPath.indexOf('.', start);
            String segment =
                    dot < 0 ? dottedPath.substring(start) : dottedPath.substring(start, dot);
            if (node.isObject() && node.has(segment)) {
                node = node.get(segment);
            } else {
                return null;
            }
            if (dot < 0) {
                return node;
            }
            start = dot + 1;
        }
        return null;
    }
}
