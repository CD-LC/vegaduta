package com.agenticai.core.agents.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * {@link ToolRelevanceScoring} is the selector's deterministic arithmetic: tokenisation,
 * typo-tolerant lexical overlap, cosine similarity and the schema-token budget. These pin the
 * fuzzy-match bounds (a typo rescues a near-miss; a short token never fuzzy-matches) and the
 * admission rule (one guaranteed pick per family, then budget-fitting extras).
 */
class ToolRelevanceScoringTest {

    private static ToolCallback tool(String name, String description, String inputSchema) {
        ToolDefinition definition = mock(ToolDefinition.class);
        when(definition.name()).thenReturn(name);
        when(definition.description()).thenReturn(description);
        when(definition.inputSchema()).thenReturn(inputSchema);
        ToolCallback callback = mock(ToolCallback.class);
        when(callback.getToolDefinition()).thenReturn(definition);
        return callback;
    }

    @Test
    void scoreCountsToolTokensPresentInTheQuery() {
        ToolCallback t = tool("web_search", "Search the web for current facts", "{}");

        assertEquals(2, ToolRelevanceScoring.score(Set.of("search", "web"), t));
        assertEquals(0, ToolRelevanceScoring.score(Set.of("unrelated"), t));
    }

    @Test
    void scoreCountsAFuzzyHitAsPresent() {
        // "pupeeter" is one deletion from "puppeteer": distance 1 within the allowed 2.
        ToolCallback t = tool("puppeteer_navigate", "Open a page in the browser", "{}");

        assertTrue(ToolRelevanceScoring.score(Set.of("pupeeter"), t) > 0);
    }

    @Test
    void nearMatchBoundsAreEnforced() {
        assertFalse(ToolRelevanceScoring.hasNearMatch("api", Set.of("app")),
            "tokens under five characters never fuzzy-match");
        assertFalse(ToolRelevanceScoring.hasNearMatch("search", Set.of("serxhh")),
            "a five-letter token allows distance 1 only");
        assertTrue(ToolRelevanceScoring.hasNearMatch("search", Set.of("serch")));
        assertTrue(ToolRelevanceScoring.hasNearMatch("puppeteer", Set.of("pupeeter")),
            "a seven-plus letter token allows distance 2");
        assertFalse(ToolRelevanceScoring.hasNearMatch("puppeteer", Set.of("pptr")),
            "the query token itself must be long enough");
    }

    @Test
    void levenshteinIsTheStandardEditDistance() {
        assertEquals(0, ToolRelevanceScoring.levenshtein("same", "same"));
        assertEquals(1, ToolRelevanceScoring.levenshtein("cat", "cut"));
        assertEquals(3, ToolRelevanceScoring.levenshtein("kitten", "sitting"));
        assertEquals(2, ToolRelevanceScoring.levenshtein("puppeteer", "pupeeter"));
        assertEquals(4, ToolRelevanceScoring.levenshtein("", "abcd"));
    }

    @Test
    void tokenizeDropsStopwordsAndPunctuation() {
        Set<String> tokens = ToolRelevanceScoring.tokenize("Please, search the web for it!");

        assertEquals(Set.of("search", "web"), tokens);
        assertFalse(tokens.contains("the"));
        assertFalse(tokens.contains("please"));
    }

    @Test
    void cosineSimilarityMatchesTheMathAndZeroVectorsScoreZero() {
        assertEquals(1.0, ToolRelevanceScoring.cosineSimilarity(
            new float[] {1, 2}, new float[] {1, 2}), 1e-6);
        assertEquals(0.0, ToolRelevanceScoring.cosineSimilarity(
            new float[] {1, 0}, new float[] {0, 1}), 1e-6);
        assertEquals(0.0, ToolRelevanceScoring.cosineSimilarity(
            new float[] {0, 0}, new float[] {1, 2}));
        // A length mismatch compares only the shared prefix, never throws.
        assertTrue(ToolRelevanceScoring.cosineSimilarity(
            new float[] {1, 0, 9}, new float[] {1, 0}) > 0.9);
    }

    @Test
    void schemaTokensAreLengthOverFour() {
        assertEquals(0, ToolRelevanceScoring.estimateSchemaTokens(tool("t", "d", null)));
        assertEquals(0, ToolRelevanceScoring.estimateSchemaTokens(tool("t", "d", "")));
        assertEquals(2, ToolRelevanceScoring.estimateSchemaTokens(tool("t", "d", "12345678")));
        assertEquals(5, ToolRelevanceScoring.totalSchemaTokens(List.of(
            tool("a", "d", "12345678"), tool("b", "d", "123456789012"))));
    }

    @Test
    void admitExtrasTakesEachGuaranteedFamilyFirst() {
        ToolCallback huge = tool("huge", "d", "x".repeat(400));
        ToolCallback familyB = tool("b1", "d", "{}");
        ToolCallback familyB2 = tool("b2", "d", "{}");
        ToolCallback cheap = tool("cheap", "d", "x".repeat(8));

        int[] used = {0};
        List<ToolCallback> picked = ToolRelevanceScoring.admitExtras(
            List.of(huge, familyB, familyB2, cheap),
            4,
            3, // a three-token budget: the 100-token schema can never fit
            used,
            t -> t.getToolDefinition().name().startsWith("b") ? "b-family" : null);

        assertEquals(List.of(familyB, familyB2, cheap), picked,
            "one guaranteed b-family pick first, then its sibling and the cheap extra fit the budget");
        assertFalse(picked.contains(huge));
        assertEquals(2, used[0], "only the admitted extras counted toward the budget");
    }

    @Test
    void admitExtrasHonoursTheCountCap() {
        ToolCallback a = tool("a1", "d", "{}");
        ToolCallback b = tool("a2", "d", "{}");
        ToolCallback c = tool("c", "d", "{}");

        int[] used = {0};
        List<ToolCallback> picked = ToolRelevanceScoring.admitExtras(
            List.of(a, b, c), 2, 1_000, used, t -> null);

        assertEquals(List.of(a, b), picked, "input order is preserved up to the cap");
    }
}
