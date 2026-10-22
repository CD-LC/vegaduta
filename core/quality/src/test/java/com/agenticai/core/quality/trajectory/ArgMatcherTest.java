package com.agenticai.core.quality.trajectory;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class ArgMatcherTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode json(String s) {
        try {
            return MAPPER.readTree(s);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static ArgMatcher matcher(String spec) {
        return ArgMatcher.parse("arg", json(spec));
    }

    @Test
    void literalsCompareByJsonValue() {
        assertTrue(matcher("5").matches(json("5")));
        assertTrue(matcher("5").matches(json("5.0")));
        assertFalse(matcher("5").matches(json("6")));
        assertFalse(matcher("5").matches(json("\"5\"")));
        assertTrue(matcher("\"fast\"").matches(json("\"fast\"")));
        assertFalse(matcher("\"fast\"").matches(json("\"FAST\"")));
        assertTrue(matcher("true").matches(json("true")));
        assertTrue(matcher("[1,2]").matches(json("[1,2.0]")));
        assertFalse(matcher("[1,2]").matches(json("[2,1]")));
        // Object literals ignore key order.
        assertTrue(matcher("{\"a\":1,\"b\":[true]}").matches(json("{\"b\":[true],\"a\":1}")));
        assertFalse(matcher("{\"a\":1}").matches(json("{\"a\":1,\"b\":2}")));
        // An absent value never equals a literal.
        assertFalse(matcher("5").matches(null));
        assertFalse(matcher("5").matches(json("null")));
    }

    @Test
    void explicitEqualsAllowsAnObjectWithAMatcherLikeKey() {
        ArgMatcher m = matcher("{\"equals\":{\"regex\":\"x\"}}");
        assertTrue(m.matches(json("{\"regex\":\"x\"}")));
        assertFalse(m.matches(json("\"x\"")));
    }

    @Test
    void presentAndAbsent() {
        assertTrue(matcher("{\"present\":true}").matches(json("0")));
        assertTrue(matcher("{\"present\":true}").matches(json("\"\"")));
        assertFalse(matcher("{\"present\":true}").matches(null));
        assertFalse(matcher("{\"present\":true}").matches(json("null")));
        assertTrue(matcher("{\"absent\":true}").matches(null));
        assertTrue(matcher("{\"absent\":true}").matches(json("null")));
        assertFalse(matcher("{\"absent\":true}").matches(json("false")));
        // A bare null literal means "absent".
        assertTrue(matcher("null").matches(null));
        assertFalse(matcher("null").matches(json("1")));
    }

    @Test
    void regexUsesFindAndIgnoreCaseOnTheValueText() {
        ArgMatcher m = matcher("{\"regex\":\"refund\\\\s+order\"}");
        assertTrue(m.matches(json("\"please refund   order 12\"")));
        assertFalse(m.matches(json("\"Refund order\"")));
        assertTrue(
                matcher("{\"regex\":\"refund\",\"ignoreCase\":true}").matches(json("\"REFUND\"")));
        // Non-text scalars match on their text; containers on their JSON.
        assertTrue(matcher("{\"regex\":\"^42$\"}").matches(json("42")));
        assertTrue(matcher("{\"regex\":\"\\\"id\\\":7\"}").matches(json("{\"id\":7}")));
        assertFalse(matcher("{\"regex\":\"x\"}").matches(null));
    }

    @Test
    void aValueTooLongForRegexMatchingNeverMatches() {
        ArgMatcher m = matcher("{\"regex\":\"a\"}");
        assertTrue(
                m.matches(json("\"" + "b".repeat(ArgMatcher.MAX_REGEX_INPUT_CHARS - 10) + "a\"")));
        assertFalse(
                m.matches(json("\"" + "a".repeat(ArgMatcher.MAX_REGEX_INPUT_CHARS + 1) + "\"")));
    }

    @Test
    void aHostilePatternOnAHostileValueFailsTheMatchInsteadOfHanging() {
        // Whatever the engine does with this shape, the call must return promptly with "no match".
        Pattern hostile = Pattern.compile("(a+)+b");
        String value = "a".repeat(5_000);
        long start = System.nanoTime();
        assertFalse(BoundedRegexMatcher.find(hostile, value, 100_000));
        assertTrue((System.nanoTime() - start) / 1_000_000L < 5_000);
        // Deterministic proof the budget is enforced: a tiny budget turns a would-be match into "no
        // match".
        assertTrue(BoundedRegexMatcher.find(Pattern.compile("a"), "a", 10));
        assertFalse(BoundedRegexMatcher.find(Pattern.compile("z"), "a".repeat(1_000), 10));
        assertFalse(BoundedRegexMatcher.find(Pattern.compile("a$"), "b".repeat(1_000) + "a", 10));
    }

    @Test
    void malformedMatchersAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> matcher("{\"regex\":\"(\"}"));
        assertThrows(IllegalArgumentException.class, () -> matcher("{\"regex\":5}"));
        assertThrows(
                IllegalArgumentException.class, () -> matcher("{\"regex\":\"a\",\"absent\":true}"));
        assertThrows(
                IllegalArgumentException.class,
                () -> matcher("{\"regex\":\"a\",\"ignoreCase\":\"yes\"}"));
        assertThrows(IllegalArgumentException.class, () -> matcher("{\"absent\":false}"));
        assertThrows(IllegalArgumentException.class, () -> matcher("{\"regex\":\"a\",\"nope\":1}"));
    }

    @Test
    void dottedPathsWalkObjectsOnly() {
        JsonNode root = json("{\"a\":{\"b\":{\"c\":1}},\"list\":[{\"x\":1}],\"s\":\"text\"}");
        assertTrue(ExpectedStep.valueAt(root, "a.b.c").asInt() == 1);
        assertTrue(ExpectedStep.valueAt(root, "a.b").isObject());
        assertTrue(ExpectedStep.valueAt(root, "missing") == null);
        assertTrue(ExpectedStep.valueAt(root, "a.x.c") == null);
        assertTrue(ExpectedStep.valueAt(root, "list.x") == null);
        assertTrue(ExpectedStep.valueAt(root, "s.length") == null);
    }
}
