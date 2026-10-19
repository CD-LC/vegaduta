package com.agenticai.core.knowledge.rag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.knowledge.dto.SearchResultResponse;
import com.agenticai.core.knowledge.rag.RagDtos.Interval;
import com.agenticai.core.knowledge.rag.RagDtos.RagEvalQuestion;
import com.agenticai.core.knowledge.rag.RagEvalMetrics.QuestionOutcome;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** hit-rate / recall@k / MRR / Wilson interval, as pure functions. */
class RagEvalMetricsTest {

    private static final UUID DOC_A = UUID.randomUUID();
    private static final UUID DOC_B = UUID.randomUUID();

    private static SearchResultResponse hit(UUID doc, String content) {
        return new SearchResultResponse(UUID.randomUUID(), "f", 0, content, UUID.randomUUID(), 0.0, doc);
    }

    private static RagEvalQuestion byDocs(UUID... docs) {
        return new RagEvalQuestion("q", Set.of(docs), null);
    }

    private static RagEvalQuestion bySubstrings(String... subs) {
        return new RagEvalQuestion("q", null, List.of(subs));
    }

    @Test
    void firstRelevantRankDrivesReciprocalRankAndHit() {
        List<SearchResultResponse> hits = List.of(hit(DOC_B, "x"), hit(UUID.randomUUID(), "y"), hit(DOC_A, "z"));

        QuestionOutcome o = RagEvalMetrics.score(byDocs(DOC_A), hits, 5);

        assertTrue(o.hit());
        assertEquals(3, o.firstRelevantRank());
        assertEquals(1.0 / 3, o.reciprocalRank(), 1e-12);
        assertEquals(1.0, o.recall());
        assertEquals(3, o.returned());
    }

    @Test
    void aRelevantHitBeyondKDoesNotCount() {
        List<SearchResultResponse> hits = List.of(hit(DOC_B, "x"), hit(DOC_B, "y"), hit(DOC_A, "z"));

        QuestionOutcome o = RagEvalMetrics.score(byDocs(DOC_A), hits, 2);

        assertFalse(o.hit());
        assertNull(o.firstRelevantRank());
        assertEquals(0.0, o.reciprocalRank());
        assertEquals(0.0, o.recall());
        assertEquals(2, o.returned());
    }

    @Test
    void recallIsTheShareOfExpectedTargetsFound() {
        List<SearchResultResponse> hits = List.of(hit(DOC_A, "Refunds within 14 days"), hit(null, "unrelated"));
        RagEvalQuestion q = new RagEvalQuestion("q", Set.of(DOC_A, DOC_B), List.of("14 days", "no such text"));

        QuestionOutcome o = RagEvalMetrics.score(q, hits, 5);

        assertEquals(2.0 / 4.0, o.recall(), 1e-12, "doc A and the substring '14 days' found; doc B and the other substring missed");
        assertTrue(o.hit());
    }

    @Test
    void substringMatchIsCaseAndWhitespaceInsensitive() {
        List<SearchResultResponse> hits = List.of(hit(null, "Refunds are issued\nwithin   14 DAYS of delivery."));

        assertTrue(RagEvalMetrics.score(bySubstrings("within 14 days"), hits, 3).hit());
        assertFalse(RagEvalMetrics.score(bySubstrings("within 30 days"), hits, 3).hit());
    }

    @Test
    void returnedCharsSumTheTopKContent() {
        List<SearchResultResponse> hits = List.of(hit(null, "12345"), hit(null, "123"), hit(null, "ignored beyond k"));

        assertEquals(8, RagEvalMetrics.score(bySubstrings("zzz"), hits, 2).returnedChars());
    }

    @Test
    void aggregateAveragesAndCountsFailures() {
        List<QuestionOutcome> outcomes = List.of(
            new QuestionOutcome(true, 1, 1.0, 5, 100, false),
            new QuestionOutcome(true, 2, 0.5, 5, 300, false),
            new QuestionOutcome(false, null, 0.0, 5, 200, false),
            QuestionOutcome.failure());

        RagEvalMetrics.Aggregate agg = RagEvalMetrics.aggregate(outcomes);

        assertEquals(0.5, agg.hitRate(), 1e-12);
        assertEquals((1.0 + 0.5 + 0.0 + 0.0) / 4, agg.recallAtK(), 1e-12);
        assertEquals((1.0 + 0.5) / 4, agg.mrr(), 1e-12);
        assertEquals((100 + 300 + 200 + 0) / 4.0, agg.avgReturnedChars(), 1e-12);
        assertEquals(1, agg.failed());
        assertTrue(agg.hitRateCi().low() < 0.5 && agg.hitRateCi().high() > 0.5);
    }

    @Test
    void aggregateOfNothingIsZeroWithAnUninformativeInterval() {
        RagEvalMetrics.Aggregate agg = RagEvalMetrics.aggregate(List.of());

        assertEquals(0.0, agg.hitRate());
        assertEquals(0.0, agg.hitRateCi().low());
        assertEquals(1.0, agg.hitRateCi().high());
    }

    @Test
    void wilsonMatchesTheTextbookValues() {
        // 8 of 10 at 95%: Wilson score interval is about [0.490, 0.943].
        Interval ci = RagEvalMetrics.wilson(8, 10);
        assertEquals(0.4902, ci.low(), 5e-4);
        assertEquals(0.9433, ci.high(), 5e-4);

        // The interval stays inside [0, 1] at the extremes and is non-degenerate.
        Interval all = RagEvalMetrics.wilson(10, 10);
        assertEquals(1.0, all.high(), 1e-12);
        assertTrue(all.low() > 0.65 && all.low() < 0.75);
        Interval none = RagEvalMetrics.wilson(0, 10);
        assertEquals(0.0, none.low(), 1e-12);
        assertTrue(none.high() > 0.25 && none.high() < 0.35);

        // More data narrows it.
        Interval wide = RagEvalMetrics.wilson(5, 10);
        Interval narrow = RagEvalMetrics.wilson(50, 100);
        assertTrue(narrow.high() - narrow.low() < wide.high() - wide.low());
    }

    @Test
    void hasTargetsRequiresAUsableExpectation() {
        assertTrue(RagEvalMetrics.hasTargets(byDocs(DOC_A)));
        assertTrue(RagEvalMetrics.hasTargets(bySubstrings("x")));
        assertFalse(RagEvalMetrics.hasTargets(new RagEvalQuestion("q", null, null)));
        assertFalse(RagEvalMetrics.hasTargets(new RagEvalQuestion("q", Set.of(), List.of("  "))));
    }
}
