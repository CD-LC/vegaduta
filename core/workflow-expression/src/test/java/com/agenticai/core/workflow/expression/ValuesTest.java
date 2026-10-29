package com.agenticai.core.workflow.expression;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Direct coverage of {@link Values}: parsing, normalisation, stringification and comparisons. */
class ValuesTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode tree(String json) throws Exception {
        return MAPPER.readTree(json);
    }

    @Test
    void parseLooseParsesWholeDocumentJsonAndLeavesEverythingElseAlone() throws Exception {
        assertEquals("", Values.parseLoose(null));
        assertEquals("   ", Values.parseLoose("   "));
        assertEquals(Map.of("a", 1L), Values.parseLoose("{\"a\":1}"));
        assertEquals(List.of(1L, 2L), Values.parseLoose("[1,2]"));
        assertEquals("12 apples", Values.parseLoose("12 apples"), "trailing text is not JSON");
        assertEquals("hello", Values.parseLoose("hello"));
        assertNull(Values.parseLoose("null"));
        assertEquals(Boolean.TRUE, Values.parseLoose("true"));
        assertEquals(1.5d, Values.parseLoose("1.5"));
    }

    @Test
    void parseStrictThrowsOnInvalidJson() throws Exception {
        assertEquals(2L, Values.parseStrict("2"));
        assertThrows(
                com.fasterxml.jackson.core.JsonProcessingException.class,
                () -> Values.parseStrict("{"));
    }

    @Test
    void legacyTreeTreatsBlankAsEmptyObjectAndGarbageAsNull() throws Exception {
        assertTrue(Values.legacyTree(null).isObject());
        assertTrue(Values.legacyTree("  ").isObject());
        assertNull(Values.legacyTree("not json"));
        assertEquals(3L, Values.legacyTree("{\"n\":3}").get("n").longValue());
    }

    @Test
    void fromJsonMapsEveryJsonKind() throws Exception {
        JsonNode doc = tree("{\"b\":true,\"i\":7,\"d\":1.5,\"s\":\"x\",\"a\":[1],\"o\":{\"k\":2},\"n\":null}");
        assertEquals(Boolean.TRUE, Values.fromJson(doc.get("b")));
        assertEquals(7L, Values.fromJson(doc.get("i")));
        assertEquals(1.5d, Values.fromJson(doc.get("d")));
        assertEquals("x", Values.fromJson(doc.get("s")));
        assertEquals(List.of(1L), Values.fromJson(doc.get("a")));
        assertEquals(Map.of("k", 2L), Values.fromJson(doc.get("o")));
        assertNull(Values.fromJson(doc.get("n")));
        assertNull(Values.fromJson(null));
        assertNull(Values.fromJson(doc.get("missing")));
        assertEquals(
                1.0e26,
                Values.fromJson(
                        MAPPER.getNodeFactory()
                                .numberNode(new BigInteger("99999999999999999999999999"))),
                "a BigInteger too large for a long widens to the nearest double");
    }

    @Test
    void normalizeCoercesJavaTypesIntoTheRuntimeModel() {
        assertEquals(5L, Values.normalize(5));
        assertEquals(5L, Values.normalize((short) 5));
        assertEquals(5L, Values.normalize((byte) 5));
        assertEquals(5L, Values.normalize(5L));
        assertEquals(1.5d, Values.normalize(1.5f));
        assertEquals(1.5d, Values.normalize(1.5d));
        assertEquals(7L, Values.normalize(new BigInteger("7")));
        assertEquals(1.0e30, Values.normalize(new BigInteger("999999999999999999999999999999")));
        assertEquals(4L, Values.normalize(new BigDecimal("4.0")));
        assertEquals(4.25d, Values.normalize(new BigDecimal("4.25")));
        assertEquals(7.0d, Values.normalize(new java.util.concurrent.atomic.AtomicLong(7)));
        assertEquals("x", Values.normalize("x"));
        assertEquals(Boolean.TRUE, Values.normalize(Boolean.TRUE));
        assertEquals(Map.of("a", 1L), Values.normalize(Map.of("a", 1)));
        assertEquals(List.of(1L, "x"), Values.normalize(List.of(1, "x")));
        assertEquals("plain", Values.normalize(new Object() {
            @Override
            public String toString() {
                return "plain";
            }
        }));
    }

    @Test
    void unwrapResolvesNodeRefsAndTheNodeRoot() {
        NodeOutput out = new NodeOutput("n1", "L", "{\"a\":1}", "OK", null);
        assertEquals(Map.of("a", 1L), Values.unwrap(new Values.NodeRef(out)));
        assertNull(Values.unwrap(Values.NODE_ROOT));
        assertEquals("x", Values.unwrap("x"));
    }

    @Test
    void parseNumberOnlyAcceptsTheDocumentedGrammar() {
        assertEquals(12L, Values.parseNumber("12"));
        assertEquals(-12L, Values.parseNumber("-12"));
        assertEquals(1.5d, Values.parseNumber("1.5"));
        assertEquals(1000.0d, Values.parseNumber("1e3"));
        assertEquals(9L, Values.parseNumber(" 9 "));
        assertNull(Values.parseNumber("abc"));
        assertNull(Values.parseNumber(""));
        assertNull(Values.parseNumber(null));
        assertNull(Values.parseNumber("1".repeat(41)), "overlong input is rejected before parsing");
        assertInstanceOf(Double.class, Values.parseNumber("99999999999999999999"), "long overflow degrades to double");
    }

    @Test
    void toNumberCoercesNumericStringsAndOtherNumbers() {
        assertEquals(3L, Values.toNumber(3L));
        assertEquals(3.5d, Values.toNumber(3.5d));
        assertEquals(4.0d, Values.toNumber(4));
        assertEquals(7L, Values.toNumber("7"));
        assertNull(Values.toNumber("x"));
        assertNull(Values.toNumber(null));
        assertNull(Values.toNumber(Boolean.TRUE));
    }

    @Test
    void numStrPrintsIntegersWithoutFractionAndPlainNotation() {
        assertEquals("3", Values.numStr(3.0));
        assertEquals("3.5", Values.numStr(3.5));
        assertEquals("0.30000000000000004", Values.numStr(0.1 + 0.2));
        assertEquals("NaN", Values.numStr(Double.NaN));
        assertEquals("Infinity", Values.numStr(Double.POSITIVE_INFINITY));
        assertEquals("-Infinity", Values.numStr(Double.NEGATIVE_INFINITY));
        assertEquals("1000000000000000000", Values.numStr(1e18), "past the long range it stays plain");
    }

    @Test
    void numBoxesIntegralDoublesAsLongInsideTheSafeRange() {
        assertEquals(5L, Values.num(5.0));
        assertEquals(2.5d, Values.num(2.5));
        assertEquals(1.0e16, Values.num(1e16), "beyond 9e15 stays a double");
    }

    @Test
    void toStrStringifiesEveryRuntimeType() {
        assertEquals("", Values.toStr(null));
        assertEquals("", Values.toStr(Values.NODE_ROOT));
        assertEquals("x", Values.toStr("x"));
        assertEquals("true", Values.toStr(Boolean.TRUE));
        assertEquals("7", Values.toStr(7L));
        assertEquals("1.5", Values.toStr(1.5d));
        assertEquals("[1,2]", Values.toStr(List.of(1L, 2L)));
        assertEquals("{\"a\":1}", Values.toStr(Map.of("a", 1L)));
    }

    @Test
    void toJsonEscapesStringsAndHonoursTheCap() {
        assertEquals("\"a\\\"b\"", Values.toJson("a\"b"));
        assertEquals("\"a\\\\b\"", Values.toJson("a\\b"));
        assertEquals("\"\\n\\r\\t\\b\\f\"", Values.toJson("\n\r\t\b\f"));
        assertEquals("\"\\u0001\"", Values.toJson("\u0001"));
        assertEquals("null", Values.toJson(null));
        assertEquals("null", Values.toJson(Double.NaN), "non-finite doubles cannot appear in JSON");
        assertEquals("5", Values.toJson(new java.util.concurrent.atomic.AtomicInteger(5)));
        ExpressionException tooLarge =
                assertThrows(ExpressionException.class, () -> Values.toJson("abcdef", 3));
        assertTrue(tooLarge.getMessage().contains("too large"));
    }

    @Test
    void typeOfNamesTheRuntimeTypes() {
        assertEquals("null", Values.typeOf(null));
        assertEquals("boolean", Values.typeOf(true));
        assertEquals("number", Values.typeOf(1L));
        assertEquals("number", Values.typeOf(1.5d));
        assertEquals("string", Values.typeOf("x"));
        assertEquals("array", Values.typeOf(List.of()));
        assertEquals("object", Values.typeOf(Map.of()));
        assertEquals("object", Values.typeOf(new Object()));
        assertEquals("number", Values.typeOf(new Values.NodeRef(new NodeOutput("n", "L", "3", "OK", null))));
    }

    @Test
    void truthyFollowsTheDocumentedRules() {
        assertFalse(Values.truthy(null));
        assertFalse(Values.truthy(false));
        assertTrue(Values.truthy(true));
        assertFalse(Values.truthy(0L));
        assertTrue(Values.truthy(-1L));
        assertFalse(Values.truthy(0.0d));
        assertFalse(Values.truthy(Double.NaN));
        assertTrue(Values.truthy(0.5d));
        assertFalse(Values.truthy(""));
        assertTrue(Values.truthy("x"));
        assertTrue(Values.truthy(List.of()));
        assertTrue(Values.truthy(Map.of()));
    }

    @Test
    void looseEqualsIsStrictOnTypeExceptNumericCoercions() {
        assertTrue(Values.looseEquals(null, null));
        assertFalse(Values.looseEquals(null, 0L));
        assertTrue(Values.looseEquals(1L, 1.0d));
        assertTrue(Values.looseEquals(1L, "1"));
        assertTrue(Values.looseEquals("1.0", 1L));
        assertFalse(Values.looseEquals("x", 1L));
        assertTrue(Values.looseEquals("a", "a"));
        assertFalse(Values.looseEquals(true, "true"));
        assertTrue(Values.looseEquals(List.of(1L, Map.of("a", 2L)), List.of(1.0d, Map.of("a", 2L))));
        assertFalse(Values.looseEquals(List.of(1L), List.of(1L, 2L)));
        assertFalse(Values.looseEquals(Map.of("a", 1L), Map.of("a", 1L, "b", 2L)));
        assertFalse(Values.looseEquals(Map.of("a", 1L), Map.of("b", 1L)));
        assertFalse(Values.looseEquals(List.of(1L), "x"));
    }

    @Test
    void containerEqualityCostsBudgetSteps() {
        Budget tight = new Budget(Limits.defaults().withMaxSteps(0).withMaxWallNanos(10_000_000_000L));
        assertThrows(
                ExpressionException.class,
                () -> Values.looseEquals(List.of(1L), List.of(1L), tight));
        Budget generous =
                new Budget(Limits.defaults().withMaxSteps(100).withMaxWallNanos(10_000_000_000L));
        assertTrue(Values.looseEquals(List.of(1L), List.of(1L), generous));
        assertFalse(Values.looseEquals(List.of(1L), List.of(2L), generous));
    }

    @Test
    void compareOrdersNumbersNumericStringsAndStrings() {
        assertTrue(Values.compare(1L, 2L) < 0);
        assertTrue(Values.compare(2.0d, 1L) > 0);
        assertEquals(0, Values.compare("a", "a"));
        assertTrue(Values.compare("a", "b") < 0);
        assertTrue(Values.compare("3", 10L) < 0, "numeric strings compare numerically");
        assertTrue(Values.compare(10L, "3") > 0);
        assertThrows(ExpressionException.class, () -> Values.compare(List.of(), 1L));
        assertThrows(ExpressionException.class, () -> Values.compare("abc", 1L));
        assertThrows(ExpressionException.class, () -> Values.compare(null, 1L));
    }

    @Test
    void sortCompareIsATotalOrderWithNullsLast() {
        assertTrue(Values.sortCompare(null, 0L) > 0, "nulls sort last");
        assertTrue(Values.sortCompare(0L, null) < 0);
        assertEquals(0, Values.sortCompare(null, null));
        assertTrue(Values.sortCompare(false, true) < 0, "booleans before everything but null");
        assertTrue(Values.sortCompare(true, 1L) < 0);
        assertTrue(Values.sortCompare(1L, 2L) < 0);
        assertTrue(Values.sortCompare(2.5d, 10L) < 0);
        assertTrue(Values.sortCompare("a", "b") < 0);
        assertTrue(Values.sortCompare(1L, "a") < 0, "numbers before strings");
        assertTrue(Values.sortCompare("a", List.of()) < 0, "strings before everything else");
        assertEquals(0, Values.sortCompare(List.of(1L), Map.of()), "same-rank containers compare equal");
    }
}
