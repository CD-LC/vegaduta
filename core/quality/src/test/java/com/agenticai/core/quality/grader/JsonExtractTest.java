package com.agenticai.core.quality.grader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

/**
 * Models wrap JSON in prose and markdown fences; the extractor must find the
 * first balanced, parseable object/array without being confused by braces
 * inside strings or by truncated output. Pinned because every grader's
 * "what did the agent answer" step depends on it.
 */
class JsonExtractTest {

    @Test
    void nullBlankAndJsonFreeTextYieldNothing() {
        assertNull(JsonExtract.firstJson(null));
        assertNull(JsonExtract.firstJson("   "));
        assertNull(JsonExtract.firstJson("no json here at all"));
    }

    @Test
    void aBareDocumentParsesWhole() {
        JsonNode node = JsonExtract.firstJson("{\"a\": 1}");

        assertEquals(1, node.get("a").asInt());
    }

    @Test
    void aBareScalarParsesToo() {
        assertEquals(42, JsonExtract.firstJson("  42  ").asInt());
        assertEquals("hello", JsonExtract.firstJson("\"hello\"").asText());
    }

    @Test
    void jsonInsideProseIsFound() {
        JsonNode node = JsonExtract.firstJson("The answer is {\"score\": 0.5} as computed.");

        assertEquals(0.5, node.get("score").asDouble(), 1e-12);
    }

    @Test
    void jsonInsideAMarkdownFenceIsFound() {
        JsonNode node = JsonExtract.firstJson("```json\n{\"ok\": true}\n```");

        assertTrue(node.get("ok").asBoolean());
    }

    @Test
    void arraysAreFoundAsWellAsObjects() {
        JsonNode node = JsonExtract.firstJson("results: [1, 2, 3]");

        assertEquals(3, node.size());
    }

    @Test
    void bracesInsideStringsDoNotConfuseTheBalancer() {
        JsonNode node = JsonExtract.firstJson("prefix {\"a\": \"}]]}\"} suffix");

        assertEquals("}]]}", node.get("a").asText());
    }

    @Test
    void escapedQuotesInsideStringsDoNotEndTheStringEarly() {
        JsonNode node = JsonExtract.firstJson("{\"a\": \"say \\\"hi\\\"\"}");

        assertEquals("say \"hi\"", node.get("a").asText());
    }

    @Test
    void anUnbalancedSpanYieldsNothingRatherThanScanningOn() {
        assertNull(JsonExtract.firstJson("{ \"a\": 1"));
        assertNull(JsonExtract.firstJson("{ \"a\": {\"b\": 1}"));
    }

    @Test
    void theFirstParseableSpanWinsNotTheFirstBrace() {
        // "[broken]" balances but does not parse, so the scan moves on to the
        // object that does.
        JsonNode node = JsonExtract.firstJson("oops [broken] then {\"good\": 1}");

        assertEquals(1, node.get("good").asInt());
    }
}
