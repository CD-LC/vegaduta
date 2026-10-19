package com.agenticai.core.agents.learning.review;

import com.agenticai.core.agents.learning.LearningProposalView;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * "What I learned this week": what the person approved in the last seven days, by kind, and what still waits
 * for them. Built from the proposal store alone, with no model call. {@code text} is a plain English sentence for
 * API consumers; the web composes its own localised sentence from the counts.
 */
public record WeeklyLearningSummary(
        Instant since, int approved, Map<String, Integer> approvedByKind, int tidied, int pending, String text) {

    static final Duration WINDOW = Duration.ofDays(7);

    public static WeeklyLearningSummary compose(List<LearningProposalView> approvedRows, int pending, Instant now) {
        Instant since = now.minus(WINDOW);
        Map<String, Integer> byKind = new TreeMap<>();
        int approved = 0;
        for (LearningProposalView v : approvedRows) {
            if (v.decidedAt() != null && !v.decidedAt().isBefore(since)) {
                approved++;
                byKind.merge(v.kind(), 1, Integer::sum);
            }
        }
        int tidied = byKind.getOrDefault("CONSOLIDATION", 0);
        return new WeeklyLearningSummary(since, approved, byKind, tidied, pending, sentence(approved, byKind, pending));
    }

    private static String sentence(int approved, Map<String, Integer> byKind, int pending) {
        StringBuilder s = new StringBuilder();
        if (approved == 0) {
            s.append("Nothing new was added to what I know this week.");
        } else {
            s.append("This week I learned ").append(approved).append(approved == 1 ? " thing" : " things").append(" with your approval");
            List<String> parts = byKind.entrySet().stream()
                    .map(e -> e.getValue() + " " + e.getKey().toLowerCase(Locale.ROOT))
                    .toList();
            if (!parts.isEmpty()) {
                s.append(" (").append(String.join(", ", parts)).append(")");
            }
            s.append('.');
        }
        if (pending > 0) {
            s.append(' ').append(pending).append(pending == 1 ? " suggestion is" : " suggestions are").append(" waiting for you.");
        }
        return s.toString();
    }
}
