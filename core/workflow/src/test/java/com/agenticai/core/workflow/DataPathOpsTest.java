package com.agenticai.core.workflow;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Characterisation of {@link DataPathOps}: get / setPath / tokenize. */
class DataPathOpsTest {
    private static final ObjectMapper M = new ObjectMapper();

    private static JsonNode j(String s) throws Exception {
        return M.readTree(s);
    }

    @Test
    void tokenizeSplitsDotsBracketsAndQuotedKeys() {
        assertEquals(List.of("a", "b", 0, "c d"), DataPathOps.tokenize("a.b[0][\"c d\"]"));
        assertEquals(List.of("a", -1), DataPathOps.tokenize("a[-1]"));
        assertEquals(List.of("a", "x"), DataPathOps.tokenize("a['x']"));
        assertEquals(List.of(), DataPathOps.tokenize(""));
    }

    @Test
    void getReadsNestedValuesAndNegativeIndexes() throws Exception {
        JsonNode root = j("{\"a\":{\"b\":[1,2,3]},\"k k\":5}");
        assertEquals(3, DataPathOps.get(root, "a.b[-1]").asInt());
        assertEquals(1, DataPathOps.get(root, "a.b[0]").asInt());
        assertEquals(5, DataPathOps.get(root, "[\"k k\"]").asInt());
        assertSame(root, DataPathOps.get(root, " "));
    }

    @Test
    void getIsMissingNeverAnErrorForAbsentSteps() throws Exception {
        JsonNode root = j("{\"a\":null,\"b\":[1]}");
        assertTrue(DataPathOps.get(root, "a.x").isMissingNode());
        assertTrue(DataPathOps.get(root, "b[5]").isMissingNode());
        assertTrue(DataPathOps.get(root, "b.x").isMissingNode());
        assertTrue(DataPathOps.get(null, "a").isMissingNode());
    }

    @Test
    void setPathCreatesIntermediateObjectsAndReplacesScalars() throws Exception {
        ObjectNode root = (ObjectNode) j("{\"a\":5}");
        DataPathOps.setPath(root, "a.b.c", M.getNodeFactory().textNode("x"));
        assertEquals(j("{\"a\":{\"b\":{\"c\":\"x\"}}}"), root);
    }

    @Test
    void setPathSetsExistingArrayIndexAndRejectsMissingOnes() throws Exception {
        ObjectNode root = (ObjectNode) j("{\"l\":[1,2]}");
        DataPathOps.setPath(root, "l[-1]", M.getNodeFactory().numberNode(9));
        assertEquals(j("{\"l\":[1,9]}"), root);
        assertThrows(IllegalArgumentException.class, () -> DataPathOps.setPath(root, "l[5]", M.getNodeFactory().numberNode(1)));
        assertThrows(IllegalArgumentException.class, () -> DataPathOps.setPath(root, " ", M.getNodeFactory().numberNode(1)));
        assertThrows(IllegalArgumentException.class, () -> DataPathOps.setPath(root, "l.x", M.getNodeFactory().numberNode(1)));
    }
}
