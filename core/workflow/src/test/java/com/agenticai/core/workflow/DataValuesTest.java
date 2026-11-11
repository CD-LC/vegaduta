package com.agenticai.core.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/** {@link DataValues}: absent/empty tests, comparison text, canonical identity and JSON parsing. */
class DataValuesTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode tree(String json) throws Exception {
        return MAPPER.readTree(json);
    }

    @Test
    void isAbsentCoversNullMissingAndJsonNull() throws Exception {
        assertTrue(DataValues.isAbsent(null));
        assertTrue(DataValues.isAbsent(MAPPER.missingNode()));
        assertTrue(DataValues.isAbsent(tree("null")));
        assertFalse(DataValues.isAbsent(tree("0")));
        assertFalse(DataValues.isAbsent(tree("false")));
    }

    @Test
    void copyDeepCopiesAndMapsMissingToNull() throws Exception {
        assertTrue(DataValues.copy(null).isNull());
        assertTrue(DataValues.copy(MAPPER.missingNode()).isNull());
        JsonNode original = tree("{\"a\":[1,2]}");
        JsonNode copy = DataValues.copy(original);
        assertEquals(original, copy);
        assertNotSame(original, copy, "a result never aliases the input tree");
    }

    @Test
    void isEmptyCoversEveryEmptyShape() throws Exception {
        assertTrue(DataValues.isEmpty(null));
        assertTrue(DataValues.isEmpty(tree("null")));
        assertTrue(DataValues.isEmpty(tree("\"  \"")));
        assertTrue(DataValues.isEmpty(tree("[]")));
        assertTrue(DataValues.isEmpty(tree("{}")));
        assertFalse(DataValues.isEmpty(tree("0")));
        assertFalse(DataValues.isEmpty(tree("false")));
        assertFalse(DataValues.isEmpty(tree("[1]")));
    }

    @Test
    void textRendersEachKindTheWayItCompares() throws Exception {
        assertEquals("", DataValues.text(null));
        assertEquals("", DataValues.text(tree("null")));
        assertEquals("abc", DataValues.text(tree("\"abc\"")));
        assertEquals("12.5", DataValues.text(tree("12.500")));
        assertEquals("true", DataValues.text(tree("true")));
        assertEquals("{\"a\":1}", DataValues.text(tree("{\"a\":1}")));
        assertEquals("[1,2]", DataValues.text(tree("[1,2]")));
    }

    @Test
    void outputTextUnquotesStringsOnly() throws Exception {
        assertEquals("abc", DataValues.outputText(tree("\"abc\"")));
        assertEquals("{\"a\":1}", DataValues.outputText(tree("{\"a\":1}")));
        assertEquals("5", DataValues.outputText(tree("5")));
    }

    @Test
    void canonicalIdentitySeparatesTypesAndHonoursIgnoreCase() throws Exception {
        assertEquals("z:", DataValues.canonical(null, false));
        assertEquals("s:abc", DataValues.canonical(tree("\"abc\""), false));
        assertEquals("s:abc", DataValues.canonical(tree("\"ABC\""), true));
        assertEquals("s:ABC", DataValues.canonical(tree("\"ABC\""), false));
        assertEquals("n:1.5", DataValues.canonical(tree("1.500"), false));
        assertEquals("b:true", DataValues.canonical(tree("true"), false));
        assertEquals("j:{\"A\":1}", DataValues.canonical(tree("{\"A\":1}"), false));
        assertEquals("j:{\"a\":1}", DataValues.canonical(tree("{\"A\":1}"), true));
        assertNotEquals(
                DataValues.canonical(tree("\"1\""), false),
                DataValues.canonical(tree("1"), false),
                "\"1\" and 1 are different values");
    }

    @Test
    void parseRequiresTheWholeTextToBeJson() throws Exception {
        assertNull(DataValues.parse(MAPPER, null));
        assertNull(DataValues.parse(MAPPER, "  "));
        assertNull(DataValues.parse(MAPPER, "not json"));
        assertNull(DataValues.parse(MAPPER, "12 apples"), "trailing text means not JSON");
        assertEquals(12, DataValues.parse(MAPPER, "12").intValue());
        assertEquals("x", DataValues.parse(MAPPER, "\"x\"").asText());
        assertTrue(DataValues.parse(MAPPER, "{\"a\":1}").isObject());
    }

    @Test
    void abbreviateCutsWithAnEllipsis() {
        assertEquals("abc", DataValues.abbreviate("abc", 5));
        assertEquals("ab...", DataValues.abbreviate("abcdef", 2));
    }
}
