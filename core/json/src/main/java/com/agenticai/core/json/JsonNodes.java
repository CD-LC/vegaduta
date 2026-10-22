package com.agenticai.core.json;

import java.util.Iterator;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/**
 * Tree-model helpers wrapped so the Jackson 3 rename of {@code JsonNode#fields()} (deprecated in
 * 2.21, replaced by {@code properties()}) is a one-place change (ADR-0012 P1b).
 */
public final class JsonNodes {

    private JsonNodes() {}

    /**
     * The object node's entries in insertion order; empty for non-object nodes. Exactly {@code
     * node.properties().iterator()} (same entries in the same order).
     */
    public static Iterator<Map.Entry<String, JsonNode>> fields(JsonNode node) {
        return node.properties().iterator();
    }
}
