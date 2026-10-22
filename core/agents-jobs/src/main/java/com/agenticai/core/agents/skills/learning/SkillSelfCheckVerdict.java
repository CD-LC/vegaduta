package com.agenticai.core.agents.skills.learning;

import com.agenticai.core.json.JsonMappers;
import java.util.Locale;
import java.util.Optional;
import tools.jackson.databind.JsonNode;

/**
 * The outcome of replaying a drafted skill against the turn it came from, and the parser for the
 * judge model's reply. The reply is untrusted text: only read as JSON data, the reason is bounded
 * and single-line, and anything unreadable is "no verdict" (the draft is then queued unchecked,
 * never dropped on a parse failure).
 */
public record SkillSelfCheckVerdict(Verdict verdict, String reason) {

    /** PASS: at least as good. WEAK: probably no better. FAIL: would have made the answer worse. */
    public enum Verdict {
        PASS,
        WEAK,
        FAIL
    }

    static final int MAX_REASON = 300;

    /** The verdict in the model's reply, or empty when it is missing, unknown or not JSON. */
    static Optional<SkillSelfCheckVerdict> parse(String reply) {
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
        if (!node.isObject() || !node.path("verdict").isString()) {
            return Optional.empty();
        }
        Verdict v;
        try {
            v = Verdict.valueOf(node.path("verdict").asString().trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        String reason =
                node.path("reason")
                        .asString("")
                        .replaceAll("[<>]", "")
                        .replaceAll("\\s+", " ")
                        .strip();
        if (reason.length() > MAX_REASON) {
            reason = reason.substring(0, MAX_REASON).stripTrailing();
        }
        return Optional.of(new SkillSelfCheckVerdict(v, reason));
    }
}
