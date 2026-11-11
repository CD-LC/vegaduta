package com.agenticai.core.chat.structured;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Where the JSON in a model reply is found: whole-document first (after the fence strip), then the
 * first balanced, string-aware {@code {...}} / {@code [...]} span that parses - with the
 * OBJECT-before-ARRAY preference {@link JsonLocator.Shape#ANY} documents.
 */
class JsonLocatorTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private static String textOf(Optional<JsonLocator.Located> found) {
        return found.map(JsonLocator.Located::text).orElse(null);
    }

    @Test
    void nothingToFindInNullOrBlankReplies() {
        assertTrue(JsonLocator.locate(mapper, null, JsonLocator.Shape.ANY).isEmpty());
        assertTrue(JsonLocator.locate(mapper, "", JsonLocator.Shape.ANY).isEmpty());
        assertTrue(JsonLocator.locate(mapper, "   ", JsonLocator.Shape.ANY).isEmpty());
    }

    @Test
    void aWholeDocumentReplyIsTakenAsIsOnceFencesAreOff() {
        Optional<JsonLocator.Located> found =
                JsonLocator.locate(mapper, "```json\n{\"a\": 1}\n```", JsonLocator.Shape.ANY);

        assertTrue(found.isPresent());
        assertEquals("{\"a\": 1}", found.get().text());
        assertEquals(1, found.get().node().get("a").asInt());
    }

    @Test
    void theFirstBalancedSpanInsideProseWins() {
        String reply = "Sure! Here is the answer: {\"score\": 0.9} - hope that helps.";

        Optional<JsonLocator.Located> found =
                JsonLocator.locate(mapper, reply, JsonLocator.Shape.OBJECT);

        assertEquals("{\"score\": 0.9}", textOf(found));
    }

    @Test
    void theShapeFilterSkipsSpansOfTheWrongKind() {
        String reply = "first an array [1, 2] then the object {\"a\": 1}";

        assertEquals(
                "{\"a\": 1}", textOf(JsonLocator.locate(mapper, reply, JsonLocator.Shape.OBJECT)));
        assertEquals("[1, 2]", textOf(JsonLocator.locate(mapper, reply, JsonLocator.Shape.ARRAY)));
        // ANY prefers the object over the earlier array - prose brackets are usually citations.
        assertEquals(
                "{\"a\": 1}", textOf(JsonLocator.locate(mapper, reply, JsonLocator.Shape.ANY)));
    }

    @Test
    void anyFallsBackToAnArrayWhenNoObjectExists() {
        assertEquals(
                "[1, 2]",
                textOf(
                        JsonLocator.locate(
                                mapper, "the list is [1, 2] ok?", JsonLocator.Shape.ANY)));
    }

    @Test
    void bracesInsideStringValuesDoNotEndTheSpanEarly() {
        String reply = "result: {\"text\": \"a } brace and a ] bracket\", \"n\": 2}";

        Optional<JsonLocator.Located> found =
                JsonLocator.locate(mapper, reply, JsonLocator.Shape.OBJECT);

        assertTrue(found.isPresent());
        assertEquals("a } brace and a ] bracket", found.get().node().get("text").asText());
    }

    @Test
    void escapedQuotesInsideStringsDoNotReopenBraces() {
        String reply = "{\"q\": \"a \\\"quoted\\\" {word}\"}";

        Optional<JsonLocator.Located> found =
                JsonLocator.locate(mapper, reply, JsonLocator.Shape.OBJECT);

        assertTrue(found.isPresent());
        assertEquals("a \"quoted\" {word}", found.get().node().get("q").asText());
    }

    @Test
    void anUnparseableCandidateIsSkippedForTheNextOne() {
        String reply = "{not json} but {\"valid\": true}";

        assertEquals(
                "{\"valid\": true}",
                textOf(JsonLocator.locate(mapper, reply, JsonLocator.Shape.OBJECT)));
    }

    @Test
    void aReplyWithNoParseableJsonAtAllIsEmpty() {
        assertTrue(
                JsonLocator.locate(mapper, "no json here at all", JsonLocator.Shape.ANY).isEmpty());
        assertTrue(JsonLocator.locate(mapper, "{unclosed", JsonLocator.Shape.OBJECT).isEmpty());
        // A valid document of the WRONG shape is still a miss.
        assertTrue(JsonLocator.locate(mapper, "[1, 2, 3]", JsonLocator.Shape.OBJECT).isEmpty());
    }

    @Test
    void aScalarWholeReplyIsLocatedUnderAny() {
        Optional<JsonLocator.Located> found =
                JsonLocator.locate(mapper, "42", JsonLocator.Shape.ANY);

        assertTrue(found.isPresent());
        assertEquals(42, found.get().node().asInt());
    }

    @Test
    void theUnstrippedReplyGetsOneScanWhenTheFenceStripCutTheJson() {
        // An unclosed fence whose JSON itself contains ``` inside a string value: the strip
        // truncates mid-document, so the fallback scan of the raw reply is the one that lands.
        String raw = "```json\n{\"code\": \"x```y\", \"n\": 1}";
        Optional<JsonLocator.Located> found =
                JsonLocator.locate(mapper, raw, JsonLocator.Shape.OBJECT);

        assertTrue(found.isPresent());
        assertEquals(1, found.get().node().get("n").asInt());
    }

    // --- matchingClose / parseStrict: the primitives the scan is built from -------

    @Test
    void matchingCloseHonoursNestingAndStrings() {
        assertEquals(5, JsonLocator.matchingClose("{[()]}", 0));
        assertEquals(-1, JsonLocator.matchingClose("{", 0));
        assertEquals(-1, JsonLocator.matchingClose("{]", 0));
        assertEquals(-1, JsonLocator.matchingClose("}", 0));
        // A closer inside a string is not a closer.
        assertEquals(4, JsonLocator.matchingClose("{\"}\"}x", 0));
        // Deep nesting past the initial 16-slot stack grows it.
        String deep = "[".repeat(40) + "]".repeat(40);
        assertEquals(79, JsonLocator.matchingClose(deep, 0));
    }

    @Test
    void parseStrictRejectsTrailingContentNullsAndBlanks() {
        assertNull(JsonLocator.parseStrict(mapper, null));
        assertNull(JsonLocator.parseStrict(mapper, "  "));
        assertNull(JsonLocator.parseStrict(mapper, "not json"));
        assertNull(JsonLocator.parseStrict(mapper, "{\"a\":1} trailing"));
        JsonNode node = JsonLocator.parseStrict(mapper, "{\"a\":1}");
        assertEquals(1, node.get("a").asInt());
    }
}
