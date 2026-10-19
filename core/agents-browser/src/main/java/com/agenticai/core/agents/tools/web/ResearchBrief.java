package com.agenticai.core.agents.tools.web;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The structured brief a planner (the calling model) hands to {@code deep_research}.
 *
 * @param question the research question, required
 * @param subQuestions independent angles to search in parallel (may be empty)
 * @param effort {@code auto|simple|comparison|complex}; auto infers from the sub-question count
 * @param maxSources optional cap on distinct pages to read (never raises the configured ceiling)
 * @param seedUrls optional public URLs the user already named; read first, in addition to searching
 */
public record ResearchBrief(
        String question,
        List<String> subQuestions,
        String effort,
        Integer maxSources,
        List<String> seedUrls) {

    static final int MAX_SUB_QUESTIONS = 12;
    static final int MAX_SEED_URLS = 5;
    static final int MAX_TEXT = 400;

    /** Thrown with a model-readable reason when the brief is unusable. */
    public static final class InvalidBriefException extends RuntimeException {
        public InvalidBriefException(String message) {
            super(message);
        }
    }

    /** Parses and validates a decoded tool-input map. */
    public static ResearchBrief parse(Map<String, Object> args) {
        Object q = args == null ? null : args.get("question");
        String question = q == null ? "" : q.toString().strip();
        if (question.isEmpty()) {
            throw new InvalidBriefException("question is required - state the research question");
        }
        String effort = "auto";
        Object e = args.get("effort");
        if (e != null && !e.toString().isBlank()) {
            effort = e.toString().strip().toLowerCase(Locale.ROOT);
            if (!List.of("auto", "simple", "comparison", "complex").contains(effort)) {
                throw new InvalidBriefException(
                        "effort must be one of auto, simple, comparison, complex");
            }
        }
        Integer maxSources = null;
        Object ms = args.get("maxSources");
        if (ms instanceof Number n) {
            maxSources = n.intValue();
        } else if (ms instanceof String s && !s.isBlank()) {
            try {
                maxSources = Integer.valueOf(s.strip());
            } catch (NumberFormatException ignored) {
                maxSources = null;
            }
        }
        return new ResearchBrief(
                clip(question),
                strings(args.get("subQuestions"), MAX_SUB_QUESTIONS),
                effort,
                maxSources,
                strings(args.get("seedUrls"), MAX_SEED_URLS));
    }

    private static List<String> strings(Object raw, int max) {
        List<String> out = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object o : list) {
                if (o != null && !o.toString().isBlank() && out.size() < max) {
                    String s = clip(o.toString().strip());
                    if (!out.contains(s)) {
                        out.add(s);
                    }
                }
            }
        }
        return out;
    }

    private static String clip(String s) {
        return s.length() > MAX_TEXT ? s.substring(0, MAX_TEXT) : s;
    }
}
