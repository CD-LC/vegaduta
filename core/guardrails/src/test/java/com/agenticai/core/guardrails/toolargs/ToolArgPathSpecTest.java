package com.agenticai.core.guardrails.toolargs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class ToolArgPathSpecTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode json(String s) throws Exception {
        return MAPPER.readTree(s);
    }

    @Test
    void parsesFieldsIndexesAndWildcards() {
        ToolArgPathSpec p = ToolArgPathSpec.parse("a.b[*].c[2]");
        assertEquals("a", p.topLevelField());
        assertTrue(p.hasWildcard());
        assertFalse(ToolArgPathSpec.parse("a.b[0]").hasWildcard());
        assertEquals("a.b[*].c[2]", p.text());
        assertEquals("user_name-1$", ToolArgPathSpec.parse("user_name-1$").topLevelField());
    }

    @Test
    void rejectsMalformedPaths() {
        for (String bad :
                new String[] {
                    "",
                    "  ",
                    ".a",
                    "a.",
                    "a..b",
                    "a[",
                    "a[]",
                    "a[x]",
                    "a[12345]",
                    "a[*",
                    "a b",
                    "a/b",
                    "[0]",
                    "a[*]b",
                    "a.b[*]c"
                }) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> ToolArgPathSpec.parse(bad),
                    "should reject '" + bad + "'");
        }
        assertThrows(IllegalArgumentException.class, () -> ToolArgPathSpec.parse(null));
        assertThrows(
                IllegalArgumentException.class,
                () -> ToolArgPathSpec.parse("a".repeat(ToolArgPathSpec.MAX_LENGTH + 1)));
        StringBuilder deep = new StringBuilder("a");
        for (int i = 0; i < ToolArgPathSpec.MAX_STEPS; i++) {
            deep.append(".a");
        }
        assertThrows(IllegalArgumentException.class, () -> ToolArgPathSpec.parse(deep.toString()));
    }

    @Test
    void resolvesFieldsIndexesAndWildcardsWithConcretePaths() throws Exception {
        JsonNode root = json("{\"a\":{\"b\":[{\"c\":1},{\"c\":2},{\"x\":3}]},\"n\":null}");
        ToolArgPathSpec.Resolution all = ToolArgPathSpec.parse("a.b[*].c").resolve(root);
        assertEquals(2, all.hits().size());
        assertEquals("a.b[0].c", all.hits().get(0).path());
        assertEquals("a.b[1].c", all.hits().get(1).path());
        assertFalse(all.truncated());

        assertEquals(1, ToolArgPathSpec.parse("a.b[1].c").resolve(root).hits().size());
        assertEquals(0, ToolArgPathSpec.parse("a.b[9].c").resolve(root).hits().size());
        // A null value is still a hit (the evaluator decides what null means).
        assertEquals(1, ToolArgPathSpec.parse("n").resolve(root).hits().size());
        // Shapes that do not fit simply produce no hit.
        assertEquals(0, ToolArgPathSpec.parse("a.b.c").resolve(root).hits().size());
        assertEquals(0, ToolArgPathSpec.parse("a[*]").resolve(root).hits().size());
        assertEquals(0, ToolArgPathSpec.parse("missing.deeper").resolve(root).hits().size());
    }

    @Test
    void aWildcardStopsAtTheFanOutCap() throws Exception {
        StringBuilder sb = new StringBuilder("{\"x\":[");
        for (int i = 0; i < ToolArgPathSpec.MAX_FAN_OUT + 10; i++) {
            sb.append(i == 0 ? "" : ",").append(i);
        }
        sb.append("]}");
        ToolArgPathSpec.Resolution r = ToolArgPathSpec.parse("x[*]").resolve(json(sb.toString()));
        assertEquals(ToolArgPathSpec.MAX_FAN_OUT, r.hits().size());
        assertTrue(r.truncated());
    }
}
