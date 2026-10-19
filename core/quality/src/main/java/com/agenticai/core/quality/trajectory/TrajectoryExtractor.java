package com.agenticai.core.quality.trajectory;

import com.agenticai.core.json.JsonMappers;
import com.agenticai.core.quality.dataset.AgentTurnReplayer.ReplayedToolCall;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns recorded tool calls into an ordered {@link TrajectoryToolCall} list: {@code [{tool, args, ok}]}.
 *
 * <p>Three entry points over the same logic: the tool calls of a freshly replayed eval turn
 * ({@link #fromReplayed}), the {@code tool_calls_json} stored on a finished eval case result
 * ({@link #fromToolCallsJson}), and any other source of (name, input, output) rows via {@link RawCall} - an
 * adapter over the tool-call log of a live session is a one-line map into {@link #fromRaw}. (Agent Lab runs
 * are text-only and carry no tool calls, so there is nothing to extract from them.)
 *
 * <p>{@code ok} is derived from the call's OUTPUT text, mirroring how the guard chain words results for calls
 * that did not run: a result starting {@code This tool call (} (refused / blocked / failed), {@code This action (}
 * (parked for human approval), {@code This tool (} (circuit breaker), {@code Tool output from '} (withheld by a
 * safety check) or the structural-validation marker is {@code ok = false}; anything else, including a missing
 * output, is {@code ok = true}. It is a heuristic over wording, documented here because it is one.
 */
public final class TrajectoryExtractor {

    /** Calls beyond this many are dropped (the evaluator ignores them anyway). */
    public static final int MAX_STEPS = TrajectoryEvaluator.MAX_ACTUAL_STEPS;

    private static final ObjectMapper MAPPER = JsonMappers.plain();

    private TrajectoryExtractor() {
    }

    /** One recorded call from any source. {@code input} is the raw argument JSON text. */
    public record RawCall(String toolName, String input, String output) {
    }

    public static List<TrajectoryToolCall> fromReplayed(List<ReplayedToolCall> calls) {
        List<RawCall> raw = new ArrayList<>();
        if (calls != null) {
            for (ReplayedToolCall c : calls) {
                raw.add(new RawCall(c.toolName(), c.input(), c.output()));
            }
        }
        return fromRaw(raw);
    }

    /**
     * Parses the stored {@code [{"toolName":..,"input":..,"output":..}]} document. Null, blank or unparseable
     * text yields an empty trajectory (never an exception: a historical row must stay readable).
     */
    public static List<TrajectoryToolCall> fromToolCallsJson(String toolCallsJson) {
        if (toolCallsJson == null || toolCallsJson.isBlank()) {
            return List.of();
        }
        try {
            JsonNode root = MAPPER.readTree(toolCallsJson);
            List<RawCall> raw = new ArrayList<>();
            if (root != null && root.isArray()) {
                for (JsonNode entry : root) {
                    raw.add(new RawCall(
                        entry.path("toolName").asText(null), entry.path("input").asText(null),
                        entry.path("output").asText(null)));
                }
            }
            return fromRaw(raw);
        } catch (Exception e) {
            return List.of();
        }
    }

    public static List<TrajectoryToolCall> fromRaw(List<RawCall> calls) {
        List<TrajectoryToolCall> steps = new ArrayList<>();
        if (calls == null) {
            return steps;
        }
        for (RawCall c : calls) {
            if (steps.size() >= MAX_STEPS) {
                break;
            }
            steps.add(new TrajectoryToolCall(c.toolName(), parseArgs(c.input()), !looksFailed(c.output())));
        }
        return steps;
    }

    static JsonNode parseArgs(String input) {
        if (input == null || input.isBlank()) {
            return MAPPER.createObjectNode();
        }
        try {
            JsonNode node = MAPPER.readTree(input);
            return node == null ? MissingNode.getInstance() : node;
        } catch (Exception e) {
            return MissingNode.getInstance();
        }
    }

    /** See the class javadoc: wording of results for calls that did not run. */
    static boolean looksFailed(String output) {
        if (output == null) {
            return false;
        }
        return output.startsWith("This tool call (")
            || output.startsWith("This tool call failed")
            || output.startsWith("This action (")
            || output.startsWith("This tool (")
            || output.startsWith("Tool output from '")
            || output.startsWith("[TOOL_OUTPUT_VALIDATION_FAILED]");
    }
}
