package com.agenticai.core.knowledge.rag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.knowledge.dto.SearchResultResponse;
import com.agenticai.core.knowledge.rag.RankFusion.RankedList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The weighted Reciprocal Rank Fusion maths, including equivalence with the legacy fixed fusion. */
class RankFusionTest {

    private static SearchResultResponse hit(UUID id) {
        return new SearchResultResponse(id, "doc", 0, "content " + id, UUID.randomUUID(), 0.0, null);
    }

    @Test
    void scoreIsTheWeightedSumOfReciprocalRanks() {
        SearchResultResponse a = hit(UUID.randomUUID());
        SearchResultResponse b = hit(UUID.randomUUID());
        SearchResultResponse c = hit(UUID.randomUUID());

        // a: rank 1 in vector, rank 2 in keyword; b: rank 2 in vector only; c: rank 1 in keyword only.
        List<SearchResultResponse> fused = RankFusion.fuse(
            List.of(new RankedList(List.of(a, b), 1.0), new RankedList(List.of(c, a), 1.0)), 60, 10);

        double scoreA = 1.0 / 61 + 1.0 / 62;
        double scoreB = 1.0 / 62;
        double scoreC = 1.0 / 61;
        assertEquals(List.of(a.id(), c.id(), b.id()), fused.stream().map(SearchResultResponse::id).toList());
        assertEquals(-scoreA, fused.get(0).distance(), 1e-12);
        assertEquals(-scoreC, fused.get(1).distance(), 1e-12);
        assertEquals(-scoreB, fused.get(2).distance(), 1e-12);
    }

    @Test
    void weightsScaleEachListsContribution() {
        SearchResultResponse a = hit(UUID.randomUUID());
        SearchResultResponse b = hit(UUID.randomUUID());

        // Vector says a, keyword says b; keyword weighted 3x must flip the winner.
        List<SearchResultResponse> equal = RankFusion.fuse(
            List.of(new RankedList(List.of(a), 1.0), new RankedList(List.of(b), 1.0)), 60, 10);
        List<SearchResultResponse> keywordHeavy = RankFusion.fuse(
            List.of(new RankedList(List.of(a), 1.0), new RankedList(List.of(b), 3.0)), 60, 10);

        assertEquals(a.id(), equal.get(0).id(), "a tie keeps first-list-first (stable) order");
        assertEquals(b.id(), keywordHeavy.get(0).id());
        assertEquals(-3.0 / 61, keywordHeavy.get(0).distance(), 1e-12);
    }

    @Test
    void kShapesHowSteeplyRankMatters() {
        SearchResultResponse a = hit(UUID.randomUUID());
        SearchResultResponse b = hit(UUID.randomUUID());
        List<RankedList> lists = List.of(new RankedList(List.of(a, b), 1.0));

        double smallK = RankFusion.fuse(lists, 1, 10).get(0).distance() - RankFusion.fuse(lists, 1, 10).get(1).distance();
        double largeK = RankFusion.fuse(lists, 1000, 10).get(0).distance() - RankFusion.fuse(lists, 1000, 10).get(1).distance();

        assertTrue(Math.abs(smallK) > Math.abs(largeK), "a small k separates adjacent ranks more");
    }

    @Test
    void defaultsReproduceTheLegacyFusionExactly() {
        // The legacy fuse() in KnowledgeSearchService: score += 1.0 / (60 + i + 1), vector list first, stable sort by score desc.
        UUID[] ids = {UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()};
        List<SearchResultResponse> vector = List.of(hit(ids[0]), hit(ids[1]), hit(ids[2]));
        List<SearchResultResponse> keyword = List.of(hit(ids[3]), hit(ids[1]), hit(ids[0]));

        List<SearchResultResponse> fused = RankFusion.fuse(
            List.of(new RankedList(vector, 1.0), new RankedList(keyword, 1.0)), 60, 3);

        assertEquals(3, fused.size(), "limit applies after sorting");
        assertEquals(ids[0], fused.get(0).id());
        assertEquals(-(1.0 / 61 + 1.0 / 63), fused.get(0).distance(), 0.0);
        assertEquals(ids[1], fused.get(1).id());
        assertEquals(-(1.0 / 62 + 1.0 / 62), fused.get(1).distance(), 0.0);
        assertEquals(ids[3], fused.get(2).id());
        assertEquals(-(1.0 / 61), fused.get(2).distance(), 0.0);
    }

    @Test
    void duplicateAcrossManyListsAccumulatesAndEmptyInputIsEmpty() {
        SearchResultResponse a = hit(UUID.randomUUID());
        List<SearchResultResponse> fused = RankFusion.fuse(
            List.of(new RankedList(List.of(a), 1.0), new RankedList(List.of(a), 1.0), new RankedList(List.of(a), 1.0)), 60, 5);
        assertEquals(1, fused.size());
        assertEquals(-3.0 / 61, fused.get(0).distance(), 1e-12);

        assertTrue(RankFusion.fuse(List.of(), 60, 5).isEmpty());
        assertTrue(RankFusion.fuse(List.of(new RankedList(List.of(), 1.0)), 60, 5).isEmpty());
    }

    @Test
    void rejectsANonPositiveK() {
        assertThrows(IllegalArgumentException.class, () -> RankFusion.fuse(List.of(), 0, 5));
    }
}
