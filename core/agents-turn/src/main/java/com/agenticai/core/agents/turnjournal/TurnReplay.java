package com.agenticai.core.agents.turnjournal;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.ai.tool.ToolCallback;

/**
 * Deterministic replay of a turn against a stubbed model (ADR-0020). The model is a pure function
 * from (user message, tool results so far) to the next step; tools are driven through the same
 * {@link JournalingToolCallback} the live path uses. Two turns agree when {@link #sameBehaviour}
 * finds the same tool-call sequence (name and argument hash) and the same final-answer hash.
 */
public final class TurnReplay {

    private TurnReplay() {}

    /** One model decision: call {@code tool} with {@code arguments}, or finish with {@code finalText}. */
    public record Step(String tool, String arguments, String finalText) {
        public static Step call(String tool, String arguments) {
            return new Step(tool, arguments, null);
        }

        public static Step finish(String text) {
            return new Step(null, null, text);
        }
    }

    @FunctionalInterface
    public interface ReplayModel {
        Step next(String userMessage, List<String> toolResults);
    }

    /** Runs one turn into {@code journal}; returns the turn id. */
    public static UUID run(
            TurnJournal journal,
            UUID agentId,
            String userMessage,
            ReplayModel model,
            Map<String, ToolCallback> tools) {
        TurnRecorder rec = TurnRecorder.start(journal, agentId);
        rec.intake(userMessage, 0, tools.size());
        Map<String, ToolCallback> wrapped = new java.util.LinkedHashMap<>();
        tools.forEach((name, cb) -> wrapped.put(name, rec.wrapTools(List.of(cb)).get(0)));
        List<String> results = new ArrayList<>();
        for (int guard = 0; guard < 32; guard++) {
            rec.modelCall("stub", "replay", "primary");
            Step step = model.next(userMessage, List.copyOf(results));
            if (step.tool() == null) {
                rec.commit(step.finalText(), "completed");
                return rec.turnId();
            }
            ToolCallback cb = wrapped.get(step.tool());
            if (cb == null) {
                throw new IllegalStateException("unknown tool in replay: " + step.tool());
            }
            results.add(cb.call(step.arguments()));
        }
        rec.commit(null, "step-limit");
        return rec.turnId();
    }

    /** Tool calls in order as {@code tool|argumentsHash}. */
    public static List<String> toolSequence(List<TurnEvent> events) {
        return events.stream()
                .filter(e -> e.type() == TurnEventType.TOOL_CALL)
                .map(e -> toolName(e.summary()) + "|" + e.payloadHash())
                .collect(Collectors.toList());
    }

    public static String finalAnswerHash(List<TurnEvent> events) {
        return events.stream()
                .filter(e -> e.type() == TurnEventType.COMMIT)
                .map(TurnEvent::payloadHash)
                .reduce((a, b) -> b)
                .orElse(null);
    }

    public static boolean sameBehaviour(List<TurnEvent> recorded, List<TurnEvent> replayed) {
        return toolSequence(recorded).equals(toolSequence(replayed))
                && java.util.Objects.equals(finalAnswerHash(recorded), finalAnswerHash(replayed));
    }

    private static String toolName(String summary) {
        int start = summary.indexOf("tool=") + 5;
        int end = summary.indexOf(' ', start);
        return summary.substring(start, end < 0 ? summary.length() : end);
    }
}
