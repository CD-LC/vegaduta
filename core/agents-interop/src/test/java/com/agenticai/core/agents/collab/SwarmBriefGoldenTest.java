package com.agenticai.core.agents.collab;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The worker brief of a verified team moved from Java string pieces into the prompt registry. With no override
 * {@code brief} is, byte for byte, what the former code built (copied below), for a goal, no goal and odd input.
 */
class SwarmBriefGoldenTest {

    private final SwarmVerifier verifier =
            new SwarmVerifier(new SwarmVerifierConfig(true, 1, 1500, 1200), (s, u) -> "{}", null);

    @Test
    void briefIsByteIdenticalToTheFormerCode() {
        for (String goal : new java.util.ArrayList<String>(List.of("Write a sourced one-page brief on X", "  padded goal  ", " ", "", "{objective} {goal} %s 100%"))) {
            for (String question : List.of("find facts", "multi\nline {subtask}", "")) {
                assertThat(verifier.brief(goal, question)).as(goal + "|" + question).isEqualTo(former(goal, question, 1200));
            }
        }
        assertThat(verifier.brief(null, "q")).isEqualTo(former(null, "q", 1200));
        assertThat(verifier.brief("g".repeat(5000), "q")).isEqualTo(former("g".repeat(5000), "q", 1200));
    }

    /** The former {@code SwarmVerifier.brief}, verbatim; {@code briefChars} is the config's fourth component (1200 here). */
    private static String former(String goal, String question, int briefChars) {
        StringBuilder sb = new StringBuilder();
        if (goal != null && !goal.isBlank()) {
            sb.append("OBJECTIVE (the overall goal your sub-task serves): ")
                .append(cap(goal.strip(), briefChars)).append("\n\n");
        }
        sb.append("YOUR SUB-TASK: ").append(question).append("\n\n")
            .append("REQUIRED OUTPUT: a concise plain-text answer that leads with the direct answer, then the key"
                + " evidence or sources.\n")
            .append("CONSTRAINTS: answer only this sub-task; do not invent facts or present assumptions as facts.\n")
            .append("UNCERTAINTY: report uncertainty explicitly - state what you could not verify and your"
                + " confidence (high/medium/low).");
        return sb.toString();
    }

    private static String cap(String s, int max) {
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }
}
