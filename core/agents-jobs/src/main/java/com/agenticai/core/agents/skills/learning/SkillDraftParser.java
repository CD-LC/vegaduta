package com.agenticai.core.agents.skills.learning;

import com.agenticai.core.json.JsonMappers;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Locale;
import java.util.Optional;

/**
 * Turns the reflective model call's reply into a skill draft. The model's output is untrusted
 * text: it is only ever read as JSON data, every field is bounded, and the result still goes
 * through {@code SkillService.propose}'s own validation before anything is stored.
 */
final class SkillDraftParser {

    static final int MAX_NAME = 48;
    static final int MIN_INSTRUCTIONS = 80;
    static final int MAX_INSTRUCTIONS = 6_000;
    static final int MAX_DESCRIPTION = 400;
    static final int MAX_RATIONALE = 500;

    record Draft(String name, String description, String instructions, String rationale) {}

    private SkillDraftParser() {}

    /** The draft, or empty when the model declined, replied with something else, or was too thin. */
    static Optional<Draft> parse(String reply) {
        if (reply == null || reply.isBlank()) {
            return Optional.empty();
        }
        int from = reply.indexOf('{');
        int to = reply.lastIndexOf('}');
        if (from < 0 || to <= from) {
            return Optional.empty();
        }
        JsonNode node;
        try {
            node = JsonMappers.plain().readTree(reply.substring(from, to + 1));
        } catch (Exception e) {
            return Optional.empty();
        }
        if (!node.isObject() || !truthy(node.path("worth_saving"), node.path("worthSaving"))) {
            return Optional.empty();
        }
        String name = slug(node.path("name").asText(""));
        String description = oneLine(node.path("description").asText(""));
        String instructions = node.path("instructions").asText("").strip();
        if (name.length() < 3
                || description.length() < 20
                || instructions.length() < MIN_INSTRUCTIONS) {
            return Optional.empty();
        }
        return Optional.of(
                new Draft(
                        name,
                        cut(description, MAX_DESCRIPTION),
                        cut(instructions, MAX_INSTRUCTIONS),
                        cut(oneLine(node.path("rationale").asText("")), MAX_RATIONALE)));
    }

    private static boolean truthy(JsonNode... candidates) {
        for (JsonNode n : candidates) {
            if (n.isBoolean()) {
                return n.asBoolean();
            }
            if (n.isTextual()) {
                return "true".equalsIgnoreCase(n.asText().trim());
            }
        }
        return false;
    }

    /** Lowercase letters, digits and single hyphens, at most {@link #MAX_NAME}. */
    static String slug(String raw) {
        String s = raw.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
        s = s.replaceAll("^-+|-+$", "");
        if (s.length() > MAX_NAME) {
            s = s.substring(0, MAX_NAME).replaceAll("-+$", "");
        }
        return s;
    }

    /** Single line, no angle brackets (descriptions are injected into prompts). */
    private static String oneLine(String text) {
        return text.replaceAll("[<>]", "").replaceAll("\\s+", " ").strip();
    }

    private static String cut(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max).stripTrailing();
    }
}
