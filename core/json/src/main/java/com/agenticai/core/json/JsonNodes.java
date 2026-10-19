package com.agenticai.core.json;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Iterator;
import java.util.Map;

/**
 * Tree-model helpers wrapped so the Jackson 3 rename of {@code JsonNode#fields()} (deprecated in
 * 2.21, replaced by {@code properties()}) is a one-place change (ADR-0012 P1b).
 */
public final class JsonNodes {

    private JsonNodes() {
    }

    /**
     * The object node's entries in insertion order; empty for non-object nodes. Exactly
     * {@code node.fields()} (a live iterator over the same entries in the same order).
     */
    @SuppressWarnings("deprecation")
    public static Iterator<Map.Entry<String, JsonNode>> fields(JsonNode node) {
        return node.fields();
    }
}
