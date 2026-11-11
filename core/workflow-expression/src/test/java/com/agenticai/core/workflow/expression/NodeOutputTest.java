package com.agenticai.core.workflow.expression;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** {@link NodeOutput}: construction, accessors and the lazily parsed value. */
class NodeOutputTest {

    @Test
    void accessorsReturnTheConstructorArguments() {
        NodeOutput n = new NodeOutput("n1", "Fetch", "raw", "SUCCESS", "2 rows");
        assertEquals("n1", n.id());
        assertEquals("Fetch", n.label());
        assertEquals("raw", n.output());
        assertEquals("SUCCESS", n.status());
        assertEquals("2 rows", n.summary());
    }

    @Test
    void nullOutputBecomesEmptyText() {
        NodeOutput n = new NodeOutput("n1", "L", null, "OK", null);
        assertEquals("", n.output());
        assertEquals("", n.value(), "empty output parses to an empty string");
    }

    @Test
    void valueParsesJsonLazilyAndCachesIt() {
        NodeOutput n = new NodeOutput("n1", "L", "{\"a\":[1,2]}", "OK", null);
        Object v = n.value();
        assertEquals(Map.of("a", List.of(1L, 2L)), v);
        assertSame(v, n.value(), "the parsed value is computed once");
    }

    @Test
    void nonJsonOutputReadsBackAsTheRawString() {
        NodeOutput n = new NodeOutput("n1", "L", "12 apples", "OK", null);
        assertEquals("12 apples", n.value());
        NodeOutput num = new NodeOutput("n2", "L", "42", "OK", null);
        assertEquals(42L, num.value());
        NodeOutput nul = new NodeOutput("n3", "L", "null", "OK", null);
        assertNull(nul.value());
    }
}
