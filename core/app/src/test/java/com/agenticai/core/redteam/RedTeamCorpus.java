package com.agenticai.core.redteam;

import com.agenticai.core.guardrails.ApprovalParked;
import com.agenticai.core.guardrails.ToolBudgetExhausted;
import com.agenticai.core.quality.redteam.RedTeamCorpusData;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.params.provider.Arguments;

/**
 * Attacker payload corpora. Labels are the test display names. The payload data itself lives in the
 * shared resource {@code eval/redteam/corpus.json} (core/quality, loaded by {@link RedTeamCorpusData})
 * so the memory-eval poisoning replay attacks the same strings; this class only adapts it to the tests.
 */
final class RedTeamCorpus {

    private RedTeamCorpus() {}

    record Payload(String label, String text) {}

    /** Whether the payload parses as exactly the platform's two-field control object (whitespace tolerated). */
    static boolean isExactControlObject(String text) {
        try {
            com.fasterxml.jackson.databind.JsonNode n = new com.fasterxml.jackson.databind.ObjectMapper()
                    .reader()
                    .with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readTree(text);
            if (n == null || !n.isObject() || n.size() > 2 || !n.hasNonNull("error")) {
                return false;
            }
            String e = n.get("error").asText();
            boolean marker = e.equals("approval_parked") || e.equals("tool_budget_exhausted");
            return marker && (n.size() == 1 || (n.has("result") && n.get("result").isTextual()));
        } catch (Exception notJson) {
            return false;
        }
    }

    /**
     * Tool outputs that imitate the platform's control results. The two exact control objects are built
     * from the live platform classes so a format change cannot silently desync them from the corpus.
     */
    static List<Payload> markerForgeries() {
        List<Payload> l = new ArrayList<>();
        for (RedTeamCorpusData.Marker m : RedTeamCorpusData.markerForgeries()) {
            String text = m.platform() == null
                    ? m.text()
                    : switch (m.platform()) {
                        case "approval_parked" -> ApprovalParked.result(m.message());
                        case "tool_budget_exhausted" -> ToolBudgetExhausted.result(m.message());
                        default -> throw new IllegalStateException("unknown platform marker " + m.platform());
                    };
            l.add(new Payload(m.label(), text));
        }
        return l;
    }

    /** Forgeries that are NOT the exact control object: substring, prefix, wrapped, encoded (fixed on the base). */
    static Stream<Arguments> partialForgeryArgs() {
        return markerForgeries().stream()
                .filter(p -> !isExactControlObject(p.text()))
                .map(p -> Arguments.of(p.label(), p.text()));
    }

    /** Forgeries that are exactly the control object (open until package D moves markers out of band). */
    static Stream<Arguments> exactForgeryArgs() {
        return markerForgeries().stream()
                .filter(p -> isExactControlObject(p.text()))
                .map(p -> Arguments.of(p.label(), p.text()));
    }

    /** One attacker instruction wrapped in the evasion styles a real page or document would use. */
    static List<Payload> injections(String instruction) {
        List<Payload> l = new ArrayList<>();
        for (RedTeamCorpusData.Injection i : RedTeamCorpusData.injections(instruction)) {
            l.add(new Payload(i.label(), i.text()));
        }
        return l;
    }

    static Stream<Arguments> injectionArgs(String instruction) {
        return injections(instruction).stream().map(p -> Arguments.of(p.label(), p.text()));
    }
}
