package com.agenticai.core.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JsonNodesTest {

    @SuppressWarnings("deprecation")
    private static List<String> viaFields(JsonNode node) {
        List<String> out = new ArrayList<>();
        node.fields().forEachRemaining(e -> out.add(e.getKey() + "=" + e.getValue()));
        return out;
    }

    private static List<String> viaHelper(JsonNode node) {
        List<String> out = new ArrayList<>();
        Iterator<Map.Entry<String, JsonNode>> it = JsonNodes.fields(node);
        it.forEachRemaining(e -> out.add(e.getKey() + "=" + e.getValue()));
        return out;
    }

    @Test
    void iteratesEntriesInInsertionOrderLikeFields() throws Exception {
        JsonNode node = JsonMappers.plain().readTree("{\"z\":1,\"a\":{\"n\":2},\"m\":[3],\"b\":null}");
        assertEquals(viaFields(node), viaHelper(node));
        assertEquals(List.of("z=1", "a={\"n\":2}", "m=[3]", "b=null"), viaHelper(node));
    }

    @Test
    void nonObjectNodesHaveNoFields() throws Exception {
        for (String json : List.of("[1,2]", "\"s\"", "3", "null", "true")) {
            assertFalse(JsonNodes.fields(JsonMappers.plain().readTree(json)).hasNext(), json);
        }
    }

    @Test
    void missingNodeHasNoFields() throws Exception {
        JsonNode root = JsonMappers.plain().readTree("{\"a\":1}");
        assertFalse(JsonNodes.fields(root.path("nope")).hasNext());
    }
}
