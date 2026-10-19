package com.agenticai.core.knowledge.rag;

import com.agenticai.core.knowledge.dto.SearchResultResponse;
import com.agenticai.core.knowledge.rag.RagDtos.Interval;
import com.agenticai.core.knowledge.rag.RagDtos.RagEvalQuestion;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Retrieval metrics for the RAG eval, as pure functions (no I/O) so the maths is unit-tested directly.
 *
 * <p>A hit is RELEVANT to a question when its {@code documentId} is one of the question's
 * {@code expectedDocumentIds}, or its text contains one of its {@code expectedSubstrings}
 * (case-insensitive, whitespace-normalised). A question's expected TARGETS are each expected document id
 * and each expected substring; a target is FOUND when some hit in the top k satisfies it.
 *
 * <ul>
 *   <li><b>hit</b> - at least one relevant hit in the top k (the per-question 0/1 behind hit-rate);</li>
 *   <li><b>recall@k</b> - found targets / all targets;</li>
 *   <li><b>reciprocal rank</b> - 1 / (1-indexed rank of the first relevant hit), 0 when none.</li>
 * </ul>
 *
 * Hit-rate gets a Wilson score interval, the same estimator {@code core/quality}'s {@code WilsonInterval}
 * uses for the platform's other evals (re-implemented here because this module cannot depend on
 * {@code core/quality}); with the sample sizes a tenant can afford the interval is wide, and the
 * console shows it so a small difference is not read as a win.
 */
public final class RagEvalMetrics {

    private static final Pattern WHITESPACE = Pattern.compile("(?U)\\s+");
    private static final double Z_95 = 1.959963984540054;

    /** One question's outcome under one config. {@code firstRelevantRank} is 1-indexed, null when no relevant hit. */
    public record QuestionOutcome(boolean hit, Integer firstRelevantRank, double recall, int returned, int returnedChars, boolean failed) {

        public double reciprocalRank() {
            return firstRelevantRank == null ? 0.0 : 1.0 / firstRelevantRank;
        }

        /** A search that threw: counts as a miss with nothing returned. */
        public static QuestionOutcome failure() {
            return new QuestionOutcome(false, null, 0.0, 0, 0, true);
        }
    }

    /** Mean metrics over a set of outcomes. */
    public record Aggregate(double hitRate, Interval hitRateCi, double recallAtK, double mrr, double avgReturnedChars, int failed) {}

    private RagEvalMetrics() {
    }

    /** True when a question has at least one usable target. */
    public static boolean hasTargets(RagEvalQuestion q) {
        boolean docs = q.expectedDocumentIds() != null && !q.expectedDocumentIds().isEmpty();
        boolean subs = q.expectedSubstrings() != null && q.expectedSubstrings().stream().anyMatch(s -> s != null && !s.isBlank());
        return docs || subs;
    }

    public static boolean relevant(RagEvalQuestion q, SearchResultResponse hit) {
        if (q.expectedDocumentIds() != null && hit.documentId() != null && q.expectedDocumentIds().contains(hit.documentId())) {
            return true;
        }
        String text = normalize(hit.content());
        if (q.expectedSubstrings() != null) {
            for (String s : q.expectedSubstrings()) {
                if (s != null && !s.isBlank() && text.contains(normalize(s))) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Scores the first {@code k} of {@code hits} (already best first). */
    public static QuestionOutcome score(RagEvalQuestion q, List<SearchResultResponse> hits, int k) {
        List<SearchResultResponse> top = hits.size() > k ? hits.subList(0, k) : hits;
        Integer firstRank = null;
        int chars = 0;
        for (int i = 0; i < top.size(); i++) {
            SearchResultResponse h = top.get(i);
            chars += h.content() == null ? 0 : h.content().length();
            if (firstRank == null && relevant(q, h)) {
                firstRank = i + 1;
            }
        }

        int targets = 0;
        int found = 0;
        if (q.expectedDocumentIds() != null) {
            for (UUID doc : q.expectedDocumentIds()) {
                targets++;
                if (top.stream().anyMatch(h -> doc.equals(h.documentId()))) {
                    found++;
                }
            }
        }
        if (q.expectedSubstrings() != null) {
            for (String s : q.expectedSubstrings()) {
                if (s == null || s.isBlank()) {
                    continue;
                }
                targets++;
                String needle = normalize(s);
                if (top.stream().anyMatch(h -> normalize(h.content()).contains(needle))) {
                    found++;
                }
            }
        }
        double recall = targets == 0 ? 0.0 : (double) found / targets;
        return new QuestionOutcome(firstRank != null, firstRank, recall, top.size(), chars, false);
    }

    public static Aggregate aggregate(List<QuestionOutcome> outcomes) {
        int n = outcomes.size();
        if (n == 0) {
            return new Aggregate(0.0, new Interval(0.0, 1.0), 0.0, 0.0, 0.0, 0);
        }
        int hits = 0;
        int failed = 0;
        double recall = 0;
        double rr = 0;
        double chars = 0;
        for (QuestionOutcome o : outcomes) {
            if (o.hit()) {
                hits++;
            }
            if (o.failed()) {
                failed++;
            }
            recall += o.recall();
            rr += o.reciprocalRank();
            chars += o.returnedChars();
        }
        return new Aggregate((double) hits / n, wilson(hits, n), recall / n, rr / n, chars / n, failed);
    }

    /** Wilson score interval for {@code successes} out of {@code n} at 95%; (0, 1) when n = 0. */
    public static Interval wilson(int successes, int n) {
        if (n <= 0) {
            return new Interval(0.0, 1.0);
        }
        double p = (double) successes / n;
        double z2 = Z_95 * Z_95;
        double denom = 1.0 + z2 / n;
        double center = (p + z2 / (2.0 * n)) / denom;
        double half = Z_95 * Math.sqrt(p * (1.0 - p) / n + z2 / (4.0 * n * (double) n)) / denom;
        return new Interval(Math.max(0.0, center - half), Math.min(1.0, center + half));
    }

    static String normalize(String s) {
        return s == null ? "" : WHITESPACE.matcher(s).replaceAll(" ").strip().toLowerCase(Locale.ROOT);
    }
}
