package com.agenticai.core.agents.e2e;

import com.agenticai.core.agents.tools.AgentStepEventEmitter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;

/**
 * One turn run for a client that asked for step events: the reply text, how the reply stream ended, and the step
 * events the turn sent beside it, in the order they were sent, parsed from the wire format
 * ({@link AgentStepEventEmitter#STEP_EVENT_MARKER} then one line of JSON).
 */
public record StepEventTurn(String reply, Throwable failure, List<JsonNode> events) {

    private static final ObjectMapper JSON = new ObjectMapper();

    public StepEventTurn {
        events = List.copyOf(events);
    }

    /** Parses the chunks the emitter put on its side channel. */
    static StepEventTurn of(String reply, Throwable failure, List<String> wireChunks) {
        List<JsonNode> parsed = new ArrayList<>();
        for (String chunk : wireChunks) {
            if (!chunk.startsWith(AgentStepEventEmitter.STEP_EVENT_MARKER)) {
                throw new AssertionError("a chunk on the step-event channel without the marker: " + chunk);
            }
            try {
                parsed.add(JSON.readTree(chunk.substring(AgentStepEventEmitter.STEP_EVENT_MARKER.length())));
            } catch (java.io.IOException e) {
                throw new AssertionError("a step event that is not JSON: " + chunk, e);
            }
        }
        return new StepEventTurn(reply, failure, parsed);
    }

    /** The events of one {@code type}, in order. */
    public List<JsonNode> of(String type) {
        return events.stream().filter(e -> type.equals(e.path("type").asText())).toList();
    }

    /** The only event of {@code type}; fails when there is none or more than one. */
    public JsonNode only(String type) {
        List<JsonNode> found = of(type);
        if (found.size() != 1) {
            throw new AssertionError("expected one " + type + " event, got " + found.size() + ": " + types());
        }
        return found.get(0);
    }

    /** The event types in the order they were sent. */
    public List<String> types() {
        return events.stream().map(e -> e.path("type").asText()).toList();
    }

    /** Index of the first event of {@code type}, or -1. */
    public int indexOf(String type) {
        return types().indexOf(type);
    }
}
