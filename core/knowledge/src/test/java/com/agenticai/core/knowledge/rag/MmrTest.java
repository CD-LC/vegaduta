package com.agenticai.core.knowledge.rag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.knowledge.dto.SearchResultResponse;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Maximal Marginal Relevance over a fused shortlist. */
class MmrTest {

    private static SearchResultResponse hit(String content, double fusedScore) {
        // Fused hits carry -score in distance (ascending = more relevant).
        return new SearchResultResponse(UUID.randomUUID(), "doc", 0, content, UUID.randomUUID(), -fusedScore, null);
    }

    private static List<String> contents(List<SearchResultResponse> hits) {
        return hits.stream().map(SearchResultResponse::content).toList();
    }

    @Test
    void lambdaOneKeepsTheInputOrder() {
        List<SearchResultResponse> ranked = List.of(
            hit("refund policy fourteen days", 0.9), hit("refund policy fourteen days again", 0.8), hit("shipping to canada", 0.7));

        assertEquals(contents(ranked), contents(Mmr.rerank(ranked, 1.0, 3)));
    }

    @Test
    void aNearDuplicateIsDemotedBelowADifferentPassage() {
        List<SearchResultResponse> ranked = List.of(
            hit("refund policy fourteen days after delivery", 0.90),
            hit("refund policy fourteen days after delivery date", 0.89), // near-duplicate of the first
            hit("shipping to canada takes five days", 0.60));

        List<SearchResultResponse> out = Mmr.rerank(ranked, 0.3, 3);

        assertEquals("refund policy fourteen days after delivery", out.get(0).content());
        assertEquals("shipping to canada takes five days", out.get(1).content(), "diverse passage jumps ahead of the duplicate");
        assertEquals("refund policy fourteen days after delivery date", out.get(2).content());
    }

    @Test
    void lambdaZeroIsPureDiversityAfterTheFirstPick() {
        List<SearchResultResponse> ranked = List.of(
            hit("alpha beta gamma", 0.9), hit("alpha beta gamma delta", 0.8), hit("completely different words here", 0.1));

        List<SearchResultResponse> out = Mmr.rerank(ranked, 0.0, 2);

        assertEquals(2, out.size());
        assertEquals("alpha beta gamma", out.get(0).content());
        assertEquals("completely different words here", out.get(1).content());
    }

    @Test
    void limitIsClampedAndDistancesStayAscendingAlongTheNewOrder() {
        List<SearchResultResponse> ranked = List.of(
            hit("one two three", 0.9), hit("one two three four", 0.8), hit("five six seven", 0.7), hit("eight nine ten", 0.6));

        List<SearchResultResponse> out = Mmr.rerank(ranked, 0.3, 100);

        assertEquals(4, out.size());
        for (int i = 1; i < out.size(); i++) {
            assertTrue(out.get(i - 1).distance() <= out.get(i).distance(), "ascending = more relevant still holds");
        }
        assertEquals(Set.copyOf(contents(ranked)), Set.copyOf(contents(out)));
        assertEquals(2, Mmr.rerank(ranked, 0.3, 2).size());
        assertTrue(Mmr.rerank(ranked, 0.3, 0).isEmpty());
        assertTrue(Mmr.rerank(List.of(), 0.5, 3).isEmpty());
    }

    @Test
    void equalScoresAreDifferentiatedByDiversityOnly() {
        List<SearchResultResponse> ranked = List.of(
            hit("same same words", 0.5), hit("same same words too", 0.5), hit("other unrelated text", 0.5));

        List<SearchResultResponse> out = Mmr.rerank(ranked, 0.5, 3);

        assertEquals("same same words", out.get(0).content());
        assertEquals("other unrelated text", out.get(1).content());
    }

    @Test
    void rejectsALambdaOutsideZeroToOne() {
        assertThrows(IllegalArgumentException.class, () -> Mmr.rerank(List.of(), -0.1, 3));
        assertThrows(IllegalArgumentException.class, () -> Mmr.rerank(List.of(), 1.1, 3));
        assertThrows(IllegalArgumentException.class, () -> Mmr.rerank(List.of(), Double.NaN, 3));
    }

    @Test
    void jaccardOfWordSets() {
        assertEquals(0.4, Mmr.jaccard(Mmr.tokenize("a1 b2 c3"), Mmr.tokenize("b2 c3 d4 e5")), 1e-12);
        assertEquals(0.0, Mmr.jaccard(Mmr.tokenize(""), Mmr.tokenize("x1")));
        assertEquals(1.0, Mmr.jaccard(Mmr.tokenize("Hello World"), Mmr.tokenize("world hello")));
    }
}
