package com.agenticai.core.knowledge.rag;

import com.agenticai.core.knowledge.dto.SearchResultResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Weighted Reciprocal Rank Fusion (Cormack, Clarke and Buettcher, "Reciprocal Rank Fusion outperforms
 * Condorcet and individual rank learning methods", SIGIR 2009): {@code score(d) = sum over every
 * ranked list L containing d of weight(L) / (k + rank_L(d))}, rank being 1-indexed.
 *
 * <p>With {@code k = 60} and every weight {@code 1.0} this is exactly the fusion
 * {@code KnowledgeSearchService} has always run (same accumulation order, same stable tie-break), which
 * is what lets the flag-off path stay byte-identical. The generalisation adds (a) a configurable
 * {@code k}, (b) a per-list weight (vector vs keyword, original query vs rewritten variants) and (c) any
 * number of lists, which the multi-query / HyDE expansion needs.
 *
 * <p>As in the original, the returned hits carry {@code -score} in {@code distance} so the platform-wide
 * "ascending = more relevant" convention still holds (see {@link SearchResultResponse}).
 */
public final class RankFusion {

    /** One ranked list (best first) and the weight its contribution is multiplied by. */
    public record RankedList(List<SearchResultResponse> results, double weight) {}

    private RankFusion() {
    }

    public static List<SearchResultResponse> fuse(List<RankedList> lists, int k, int limit) {
        if (k < 1) {
            throw new IllegalArgumentException("RRF k must be at least 1 (got " + k + ")");
        }
        Map<UUID, SearchResultResponse> byId = new LinkedHashMap<>();
        Map<UUID, Double> score = new LinkedHashMap<>();
        for (RankedList list : lists) {
            List<SearchResultResponse> results = list.results();
            for (int i = 0; i < results.size(); i++) {
                SearchResultResponse r = results.get(i);
                byId.putIfAbsent(r.id(), r);
                score.merge(r.id(), list.weight() / (k + i + 1), Double::sum);
            }
        }
        List<SearchResultResponse> out = new ArrayList<>();
        score.entrySet().stream()
            .sorted(Map.Entry.<UUID, Double>comparingByValue().reversed())
            .limit(limit)
            .forEach(e -> out.add(byId.get(e.getKey()).withDistance(-e.getValue())));
        return out;
    }
}
