package com.agenticai.core.knowledge.rag;

import com.agenticai.core.knowledge.dto.SearchResultResponse;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Maximal Marginal Relevance (Carbonell and Goldstein, SIGIR 1998) over an already fused and ranked
 * shortlist: repeatedly pick the candidate maximising
 * {@code lambda * relevance - (1 - lambda) * maxSimilarityToAlreadyPicked}. {@code lambda = 1} is pure
 * relevance (the input order), {@code lambda = 0} is pure diversity.
 *
 * <p>Relevance is the fused score min-max normalised to [0, 1] (the fused hits carry {@code -score} in
 * {@code distance}). Similarity is the Jaccard overlap of the two chunks' lowercase word sets - a
 * deliberately cheap lexical proxy: the hits carry no embedding, and fetching vectors back just to
 * de-duplicate near-identical chunks would cost a query per search. Near-duplicate chunks (the same
 * paragraph ingested from two sources, overlapping windows) have a high word-set overlap, which is
 * exactly what this is meant to demote.
 */
public final class Mmr {

    private static final Pattern TOKEN = Pattern.compile("[\\p{L}\\p{N}]{2,}");

    private Mmr() {
    }

    /**
     * @param ranked best first
     * @param lambda in [0, 1]
     * @param limit how many to select (clamped to the input size)
     * @return the selected hits in selection order. Their {@code distance} values are the original
     *     ones re-assigned in ascending order along the new order, so "ascending = more relevant"
     *     still describes the returned list.
     */
    public static List<SearchResultResponse> rerank(List<SearchResultResponse> ranked, double lambda, int limit) {
        if (lambda < 0.0 || lambda > 1.0 || Double.isNaN(lambda)) {
            throw new IllegalArgumentException("MMR lambda must be within [0, 1] (got " + lambda + ")");
        }
        int n = ranked.size();
        int want = Math.min(Math.max(limit, 0), n);
        if (want == 0) {
            return List.of();
        }
        double[] rel = relevance(ranked);
        List<Set<String>> tokens = new ArrayList<>(n);
        for (SearchResultResponse r : ranked) {
            tokens.add(tokenize(r.content()));
        }
        boolean[] taken = new boolean[n];
        double[] maxSim = new double[n];
        List<Integer> order = new ArrayList<>(want);
        for (int round = 0; round < want; round++) {
            int best = -1;
            double bestScore = Double.NEGATIVE_INFINITY;
            for (int i = 0; i < n; i++) {
                if (taken[i]) {
                    continue;
                }
                double score = lambda * rel[i] - (1.0 - lambda) * (order.isEmpty() ? 0.0 : maxSim[i]);
                if (score > bestScore) { // strict: ties keep the lower (better-ranked) index
                    bestScore = score;
                    best = i;
                }
            }
            taken[best] = true;
            order.add(best);
            for (int i = 0; i < n; i++) {
                if (!taken[i]) {
                    maxSim[i] = Math.max(maxSim[i], jaccard(tokens.get(best), tokens.get(i)));
                }
            }
        }
        double[] distances = order.stream().mapToDouble(i -> ranked.get(i).distance()).sorted().toArray();
        List<SearchResultResponse> out = new ArrayList<>(want);
        for (int pos = 0; pos < want; pos++) {
            out.add(ranked.get(order.get(pos)).withDistance(distances[pos]));
        }
        return out;
    }

    /** Min-max normalised {@code -distance}; all-equal scores normalise to 1.0 so only diversity differentiates. */
    static double[] relevance(List<SearchResultResponse> ranked) {
        int n = ranked.size();
        double[] s = new double[n];
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < n; i++) {
            s[i] = -ranked.get(i).distance();
            min = Math.min(min, s[i]);
            max = Math.max(max, s[i]);
        }
        double[] rel = new double[n];
        for (int i = 0; i < n; i++) {
            rel[i] = max > min ? (s[i] - min) / (max - min) : 1.0;
        }
        return rel;
    }

    static Set<String> tokenize(String text) {
        Set<String> out = new HashSet<>();
        if (text == null) {
            return out;
        }
        Matcher m = TOKEN.matcher(text.toLowerCase(Locale.ROOT));
        while (m.find()) {
            out.add(m.group());
        }
        return out;
    }

    static double jaccard(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) {
            return 0.0;
        }
        Set<String> small = a.size() <= b.size() ? a : b;
        Set<String> large = small == a ? b : a;
        int inter = 0;
        for (String t : small) {
            if (large.contains(t)) {
                inter++;
            }
        }
        int union = a.size() + b.size() - inter;
        return union == 0 ? 0.0 : (double) inter / union;
    }
}
