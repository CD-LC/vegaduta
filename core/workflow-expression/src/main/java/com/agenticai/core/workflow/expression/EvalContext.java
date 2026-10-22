package com.agenticai.core.workflow.expression;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/**
 * Everything an expression can read (spec 3.2). Implementations must be safe to call from the
 * evaluating thread; the library never mutates anything it is given. Values may be plain Java or
 * Jackson trees; they are normalised on first use.
 */
public interface EvalContext {

    /** This node's raw input string ({@code $input}, and {@code {{input}}}). */
    String input();

    /**
     * The input parsed the way the legacy {@code {{field}}} substitution did (blank = empty object,
     * null if not JSON). Only bare-name legacy resolution reads this; override to avoid re-parsing.
     */
    default JsonNode inputTree() {
        return Values.legacyTree(input());
    }

    /**
     * True when a bare {@code {{input}}} must mean the raw input even if the input JSON has a field
     * named "input" (agent query and CRM fields always worked that way; Transform/HTTP let the
     * field win).
     */
    default boolean rawInputWins() {
        return false;
    }

    /** Output of the upstream node with this label; null when unknown. */
    default NodeOutput nodeByLabel(String label) {
        return null;
    }

    /** Output of the upstream node with this id; null when unknown. */
    default NodeOutput nodeById(String id) {
        return null;
    }

    default Object trigger() {
        return null;
    }

    /** {@code id, startedAt, mode, requestedBy}. */
    default Map<String, Object> run() {
        return Map.of();
    }

    /** {@code id, name, version}. */
    default Map<String, Object> workflow() {
        return Map.of();
    }

    default Map<String, Object> vars() {
        return Map.of();
    }

    default Object item() {
        return null;
    }

    default Object index() {
        return null;
    }

    default List<Object> items() {
        return null;
    }

    /** Source of {@code $now} and {@code now()}. */
    default Clock clock() {
        return Clock.systemUTC();
    }
}
