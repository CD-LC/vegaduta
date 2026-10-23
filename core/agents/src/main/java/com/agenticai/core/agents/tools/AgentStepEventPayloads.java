package com.agenticai.core.agents.tools;

import com.agenticai.core.json.JsonMappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Builds the single-line JSON payloads {@link AgentStepEventEmitter} puts on the wire, one factory
 * per event {@code type}. Pure and static: no gating, no emission, no state - the emitter decides
 * whether and when an event is sent, and documents what each event means. Every free-text field
 * goes through {@link AgentStepEventRedactor} exactly as it did when these maps were built inline;
 * key order is part of the wire format and is preserved.
 *
 * <h2>Framing: the repo's existing in-band sentinel convention, not a new one</h2>
 *
 * {@link AgentStepEventEmitter#STEP_EVENT_MARKER} is a sibling of {@code AgentController#STREAM_ERROR_MARKER} and
 * follows exactly the same rules that marker established, deliberately rather than inventing a
 * second scheme:
 *
 * <ul>
 *   <li>It is an <b>in-band chunk prefix</b>, not an SSE {@code event:} name. Spring's {@code
 *       Flux<String>} + {@code text/event-stream} writer emits {@code data:<chunk>} with no
 *       separator space of its own (see the long comment in {@code web/app/lib/api.ts}), so every
 *       client discriminates purely by payload prefix after a fixed {@code slice(5)}.
 *   <li>It is namespaced with a random-looking suffix so it cannot realistically collide with real
 *       streamed model output.
 *   <li>It is mirrored byte-for-byte in each client that opts in ({@code web/app/lib/api.ts}'s
 *       {@code STEP_EVENT_MARKER}); two divergent literals would be a real bug, which is why this
 *       constant is {@code public} rather than package-private.
 * </ul>
 *
 * <p><b>The payload is always a single line.</b> That is a hard framing requirement, not a
 * stylistic one: Spring's SSE writer splits a chunk on {@code \n} into multiple {@code data:} lines
 * (this is precisely why {@code AgentChatService#INTERRUPTED_REPLY_MARKER}, which begins with two
 * newlines, arrives as three separate lines). A step event fragmented across lines would fail the
 * prefix test on every fragment and each fragment would then be appended to the visible reply as
 * literal text. Jackson's default {@code writeValueAsString} is single-line and JSON-escapes every
 * embedded newline inside string values, so this holds structurally - but it is the reason
 * pretty-printing must never be enabled on {@link #MAPPER}.
 */
final class AgentStepEventPayloads {

    /**
     * Single-line output only - see the class javadoc's framing note. Never enable pretty-printing
     * here.
     */
    private static final ObjectMapper MAPPER = JsonMappers.plain();

    private AgentStepEventPayloads() {}

    /** One payload as single-line JSON (the part after {@link AgentStepEventEmitter#STEP_EVENT_MARKER}). */
    static String toJson(Map<String, Object> event) throws JsonProcessingException {
        return MAPPER.writeValueAsString(event);
    }

    /** A liveness beat: {@code type} plus elapsed {@code ms} since {@code runStartedAt}, when known. */
    static Map<String, Object> heartbeat(Instant runStartedAt) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", "heartbeat");
        if (runStartedAt != null) {
            event.put("ms", Duration.between(runStartedAt, Instant.now()).toMillis());
        }
        return event;
    }

    /**
     * A pipeline phase began: {@code phase} names the stage ({@code guardrails}, {@code routing},
     * {@code context}, {@code model}) and {@code ms} is the time since the turn was accepted, so a
     * client can show where a silent turn is spending its time before the first token.
     */
    static Map<String, Object> phase(String phase, long elapsedMillis) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", "phase");
        event.put("phase", phase);
        event.put("ms", elapsedMillis);
        return event;
    }

    static Map<String, Object> toolCall(int id, String toolName, String arguments) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", "tool_call");
        event.put("id", id);
        event.put("tool", toolName);
        event.put("args", AgentStepEventRedactor.redactArguments(arguments));
        return event;
    }

    static Map<String, Object> toolResult(
            int stepId,
            String toolName,
            String output,
            long elapsedMillis,
            Map<String, Object> app) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", "tool_result");
        event.put("id", stepId);
        event.put("tool", toolName);
        event.put("preview", AgentStepEventRedactor.redactPreview(output));
        event.put("ms", elapsedMillis);
        if (app != null && !app.isEmpty()) {
            event.put("app", app);
        }
        return event;
    }

    static Map<String, Object> toolError(
            int stepId, String toolName, String message, long elapsedMillis) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", "error");
        event.put("id", stepId);
        event.put("tool", toolName);
        event.put("message", AgentStepEventRedactor.redactPreview(message));
        event.put("ms", elapsedMillis);
        return event;
    }

    static Map<String, Object> runStarted(String sessionId, UUID agentId) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", "run_started");
        event.put("sessionId", sessionId);
        event.put("agentId", agentId == null ? null : agentId.toString());
        return event;
    }

    static Map<String, Object> memoryRecall(
            int blockCount,
            List<String> blockLabels,
            int privateFacts,
            int sharedFacts,
            List<UUID> factIds) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", "memory");
        event.put("blocks", blockCount);
        event.put("blockLabels", blockLabels == null ? List.of() : List.copyOf(blockLabels));
        event.put("privateFacts", privateFacts);
        event.put("sharedFacts", sharedFacts);
        List<String> ids = new ArrayList<>();
        if (factIds != null) {
            for (UUID id : factIds) {
                if (id != null) {
                    ids.add(id.toString());
                }
            }
        }
        event.put("factIds", ids);
        return event;
    }

    static Map<String, Object> runFinished(long elapsedMillis) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", "run_finished");
        event.put("ms", elapsedMillis);
        return event;
    }

    static Map<String, Object> plan(String text) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", "plan");
        event.put("text", AgentStepEventRedactor.redactPreview(text));
        return event;
    }

    static Map<String, Object> thinking(String text) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", "thinking");
        event.put("text", AgentStepEventRedactor.redactPreview(text));
        return event;
    }

    static Map<String, Object> guardrail(String tool, String reason) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", "guardrail");
        event.put("tool", tool);
        event.put("reason", AgentStepEventRedactor.redactPreview(reason));
        return event;
    }

    static Map<String, Object> hook(
            String point,
            String hookId,
            String action,
            String decision,
            String tool,
            String reason) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", "hook");
        event.put("point", point);
        event.put("hook", hookId);
        event.put("action", action);
        event.put("decision", decision);
        if (tool != null) {
            event.put("tool", tool);
        }
        event.put("reason", AgentStepEventRedactor.redactPreview(reason));
        return event;
    }

    static Map<String, Object> handoff(UUID fromAgent, UUID toAgent) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", "handoff");
        event.put("fromAgent", fromAgent == null ? null : fromAgent.toString());
        event.put("toAgent", toAgent == null ? null : toAgent.toString());
        return event;
    }

    static Map<String, Object> swarm(
            String phase, String runId, List<String> members, List<Map<String, Object>> results) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", "swarm");
        event.put("phase", phase);
        event.put("runId", runId);
        if (members != null) {
            event.put("members", members);
        }
        if (results != null) {
            // Statuses only - the full answers are model context, not trace data.
            List<Map<String, Object>> summary = new ArrayList<>();
            for (Map<String, Object> r : results) {
                Map<String, Object> s = new LinkedHashMap<>();
                s.put("agent", r.get("agent"));
                s.put("status", r.get("status"));
                if (r.get("ms") != null) {
                    s.put("ms", r.get("ms"));
                }
                summary.add(s);
            }
            event.put("results", summary);
        }
        return event;
    }

    static Map<String, Object> toolSearch(String query, int matched, List<String> tools) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", "tool_search");
        event.put("query", AgentStepEventRedactor.redactPreview(query));
        event.put("matched", matched);
        if (tools != null) {
            event.put("tools", tools);
        }
        event.put(
                "summary",
                AgentStepEventRedactor.redactPreview(
                        "searched tools: " + query + " -> " + matched));
        return event;
    }

    // ------------------------------------------------------------------
    // Turn-lifecycle events. Every field is a short machine value (a code, a count, a model or
    // tool name, an id), never free text, so a client keys its chips and notices on them
    // rather than on wording. The schema is documented in docs/api/asyncapi.json (generated by
    // scripts/docs/asyncapi.mjs) and the vocabulary is {@link TurnLifecycleStepEvents}.
    // ------------------------------------------------------------------

    /** The reply ended: {@code finishReason} is one of the {@link TurnLifecycleStepEvents} finish reasons. */
    static Map<String, Object> finish(String finishReason, String detail) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", "finish");
        event.put("finishReason", finishReason);
        if (detail != null && !detail.isBlank()) {
            event.put("detail", AgentStepEventRedactor.redactPreview(detail));
        }
        return event;
    }

    /** A model call is being tried again: {@code attempt} is the try about to run (1 = the first). */
    static Map<String, Object> retry(int attempt, int maxAttempts, String reason) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", "retry");
        event.put("attempt", attempt);
        event.put("maxAttempts", maxAttempts);
        event.put("reason", reason);
        return event;
    }

    /** The turn moved to another model after the one before it failed. */
    static Map<String, Object> failover(String toModel, String reason) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", "failover");
        if (toModel != null && !toModel.isBlank()) {
            event.put("toModel", toModel);
        }
        event.put("reason", reason);
        return event;
    }

    /** Another model pass is carrying the reply on: {@code pass} of at most {@code maxPasses}. */
    static Map<String, Object> continuation(int pass, int maxPasses, String reason) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", "continuation");
        event.put("pass", pass);
        event.put("maxPasses", maxPasses);
        if (reason != null && !reason.isBlank()) {
            event.put("reason", reason);
        }
        return event;
    }

    /** A tool call is held until a person decides it; {@code approvalId} is the pending request. */
    static Map<String, Object> approvalRequired(String tool, String approvalId) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", "approval_required");
        event.put("tool", tool);
        event.put("approvalId", approvalId);
        return event;
    }

    static Map<String, Object> contextCompacted(
            int tokensBefore, int tokensAfter, int clearedToolResults, int compactedMessages) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", "context_compacted");
        event.put("tokensBefore", tokensBefore);
        event.put("tokensAfter", tokensAfter);
        event.put("cleared", clearedToolResults);
        event.put("compacted", compactedMessages);
        StringBuilder summary =
                new StringBuilder("context ")
                        .append(tokensBefore)
                        .append(" -> ")
                        .append(tokensAfter)
                        .append(" tokens");
        if (clearedToolResults > 0) {
            summary.append("; cleared ").append(clearedToolResults).append(" older tool result(s)");
        }
        if (compactedMessages > 0) {
            summary.append("; summarised ").append(compactedMessages).append(" older message(s)");
        }
        event.put("summary", summary.toString());
        return event;
    }
}
