package com.agenticai.core.agents.learning.episodes;

import com.agenticai.core.agents.learning.episodes.EpisodeSignalSource.HumanVerdict;
import com.agenticai.core.agents.learning.episodes.EpisodeSignalSource.Signals;
import com.agenticai.core.agents.tools.ToolCallLog;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

/**
 * Builds the compact episode text from VERIFIED signals only (ADR-0028 section 3): tool status,
 * approval decisions, a human verdict, and whether the platform saw the turn error out. It never
 * reads the user's message or the model's answer, so there is no model claim of success and no free
 * text to carry an injection; the only strings that come from outside the platform are tool names,
 * which are reduced to {@code [a-z0-9_.-]} and bounded. Pure and deterministic.
 */
final class EpisodeComposer {

    /** The outcome vocabulary of ADR-0028 section 3. */
    enum Outcome {
        VERIFIED_SUCCESS,
        VERIFIED_FAILURE,
        HUMAN_ACCEPTED,
        HUMAN_REJECTED,
        UNKNOWN
    }

    record Episode(String taskClass, Outcome outcome, String text) {}

    static final int MAX_TOOLS_LISTED = 5;
    static final int MAX_TOOL_NAME_CHARS = 40;

    private EpisodeComposer() {}

    static Episode compose(
            List<ToolCallLog> calls, Signals signals, boolean turnErrored, int maxChars) {
        List<ToolCallLog> list = calls == null ? List.of() : calls;
        Signals sig = signals == null ? Signals.NONE : signals;
        String taskClass = taskClass(list);
        int failedFinal = unresolvedFailures(list);
        Outcome outcome = outcome(list.size(), failedFinal, sig, turnErrored);
        String text =
                "Episode ("
                        + taskClass
                        + "): outcome="
                        + outcome
                        + "; tool calls="
                        + list.size()
                        + ", unresolved failures="
                        + failedFinal
                        + "; approvals approved="
                        + sig.approved()
                        + ", rejected="
                        + sig.rejected()
                        + "; human verdict="
                        + (sig.verdict() == null
                                ? "none"
                                : sig.verdict().name().toLowerCase(Locale.ROOT))
                        + "; turn "
                        + (turnErrored ? "errored" : "completed")
                        + ".";
        if (text.length() > maxChars) {
            text = text.substring(0, Math.max(1, maxChars));
        }
        return new Episode(taskClass, outcome, text);
    }

    private static Outcome outcome(int calls, int failedFinal, Signals sig, boolean turnErrored) {
        if (sig.verdict() == HumanVerdict.ACCEPTED) {
            return Outcome.HUMAN_ACCEPTED;
        }
        if (sig.verdict() == HumanVerdict.REJECTED || sig.rejected() > 0) {
            return Outcome.HUMAN_REJECTED;
        }
        if (turnErrored || failedFinal > 0) {
            return Outcome.VERIFIED_FAILURE;
        }
        if (calls > 0 || sig.approved() > 0) {
            return Outcome.VERIFIED_SUCCESS;
        }
        return Outcome.UNKNOWN;
    }

    /** A failed call counts only while no later call of the same tool succeeded. */
    private static int unresolvedFailures(List<ToolCallLog> calls) {
        Map<String, Boolean> lastFailed = new HashMap<>();
        calls.stream()
                .sorted(
                        java.util.Comparator.comparing(
                                ToolCallLog::getStartedAt,
                                java.util.Comparator.nullsFirst(
                                        java.util.Comparator.naturalOrder())))
                .forEach(c -> lastFailed.put(safeName(c.getToolName()), failed(c)));
        return (int) lastFailed.values().stream().filter(Boolean::booleanValue).count();
    }

    static boolean failed(ToolCallLog c) {
        return c.isValidationFailed()
                || (c.getErrorMessage() != null && !c.getErrorMessage().isBlank());
    }

    static String taskClass(List<ToolCallLog> calls) {
        TreeSet<String> names = new TreeSet<>();
        for (ToolCallLog c : calls) {
            names.add(safeName(c.getToolName()));
        }
        if (names.isEmpty()) {
            return "chat";
        }
        return "tools:" + String.join("+", names.stream().limit(MAX_TOOLS_LISTED).toList());
    }

    static String safeName(String raw) {
        if (raw == null || raw.isBlank()) {
            return "unknown";
        }
        String s = raw.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.-]", "_");
        return s.length() > MAX_TOOL_NAME_CHARS ? s.substring(0, MAX_TOOL_NAME_CHARS) : s;
    }
}
