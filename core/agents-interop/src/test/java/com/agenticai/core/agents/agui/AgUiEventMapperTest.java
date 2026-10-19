package com.agenticai.core.agents.agui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.agents.core.AgentController;
import com.agenticai.core.agents.tools.AgentStepEventEmitter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

/**
 * Golden test for {@link AgUiEventMapper}: a synthetic chat stream - tokens plus {@code
 * STEP_EVENT_MARKER}-framed step events, byte-for-byte what {@code AgentController#chat} emits for
 * a {@code stepEvents:true} turn - must map to the exact ordered AG-UI event list, with every start
 * paired to its end and the run's lifecycle bookends first and last.
 *
 * <p>Ids and timestamps are made deterministic through the mapper's test constructor so the
 * assertions are on literal values, not shapes. The fixtures build step-event chunks with the same
 * marker constant the emitter uses, so a change to the marker cannot silently desynchronize this
 * test from production framing.
 */
class AgUiEventMapperTest {

    private static final String MARKER = AgentStepEventEmitter.STEP_EVENT_MARKER;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final UUID AGENT_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");

    private AgUiEventMapper mapper;
    private AtomicInteger idCounter;

    @BeforeEach
    void deterministicMapper() {
        idCounter = new AtomicInteger();
        // ids: id-1, id-2, ...; clock: a fixed instant so timestamps are stable.
        mapper =
                new AgUiEventMapper(
                        () -> "id-" + idCounter.incrementAndGet(), () -> 1_700_000_000_000L);
    }

    private List<AgUiEvent> run(String... chunks) {
        return mapper.map(Flux.fromArray(chunks), "thread-1", "run-1", AGENT_ID)
                .collectList()
                .block();
    }

    private static String step(String type, Object... keyValues) {
        Map<String, Object> event = new java.util.LinkedHashMap<>();
        event.put("type", type);
        for (int i = 0; i < keyValues.length; i += 2) {
            event.put((String) keyValues[i], keyValues[i + 1]);
        }
        try {
            return MARKER + JSON.writeValueAsString(event);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static List<String> types(List<AgUiEvent> events) {
        List<String> types = new ArrayList<>(events.size());
        for (AgUiEvent event : events) {
            types.add(event.type());
        }
        return types;
    }

    /** The audit's exact golden case: two tokens, a tool call, its result, then more text. */
    @Test
    void tokensAndStepEventsMapToTheOrderedProtocolSequence() {
        List<AgUiEvent> events =
                run(
                        "Hel",
                        "lo",
                        step(
                                "tool_call",
                                "id",
                                1,
                                "tool",
                                "search_knowledge_base",
                                "args",
                                "{\"query\":\"pricing\"}"),
                        step(
                                "tool_result",
                                "id",
                                1,
                                "tool",
                                "search_knowledge_base",
                                "preview",
                                "3 hits",
                                "ms",
                                42),
                        " world");

        assertEquals(
                List.of(
                        "RUN_STARTED",
                        "STATE_SNAPSHOT",
                        "TEXT_MESSAGE_START",
                        "TEXT_MESSAGE_CONTENT",
                        "TEXT_MESSAGE_CONTENT",
                        "TEXT_MESSAGE_END",
                        "TOOL_CALL_START",
                        "TOOL_CALL_ARGS",
                        "TOOL_CALL_END",
                        "TOOL_CALL_RESULT",
                        "TEXT_MESSAGE_START",
                        "TEXT_MESSAGE_CONTENT",
                        "TEXT_MESSAGE_END",
                        "RUN_FINISHED"),
                types(events));

        // Lifecycle bookends carry the thread/run ids and are first/last.
        assertEquals("thread-1", events.get(0).threadId());
        assertEquals("run-1", events.get(0).runId());
        assertEquals("thread-1", events.get(events.size() - 1).threadId());
        assertEquals("run-1", events.get(events.size() - 1).runId());

        // The snapshot binds the platform's own identifiers for the client.
        assertEquals("thread-1", events.get(1).snapshot().get("sessionId"));
        assertEquals(AGENT_ID.toString(), events.get(1).snapshot().get("agentId"));

        // First assistant message: start/content/content/end share one id.
        String firstMessage = events.get(2).messageId();
        assertEquals("assistant", events.get(2).role());
        assertEquals(firstMessage, events.get(3).messageId());
        assertEquals("Hel", events.get(3).delta());
        assertEquals(firstMessage, events.get(4).messageId());
        assertEquals("lo", events.get(4).delta());
        assertEquals(firstMessage, events.get(5).messageId());

        // The tool call hangs off that message, and its three events share one toolCallId.
        String toolCallId = events.get(6).toolCallId();
        assertEquals("search_knowledge_base", events.get(6).toolCallName());
        assertEquals(firstMessage, events.get(6).parentMessageId());
        assertEquals(toolCallId, events.get(7).toolCallId());
        assertEquals("{\"query\":\"pricing\"}", events.get(7).delta());
        assertEquals(toolCallId, events.get(8).toolCallId());
        assertEquals(toolCallId, events.get(9).toolCallId());
        assertEquals("3 hits", events.get(9).content());
        assertEquals("tool", events.get(9).role());
        assertNotNull(events.get(9).messageId());

        // Text after the tool call is a NEW message with a different id.
        String secondMessage = events.get(10).messageId();
        assertFalse(firstMessage.equals(secondMessage));
        assertEquals(" world", events.get(11).delta());
        assertEquals(secondMessage, events.get(12).messageId());

        assertEveryIdPairs(events);
    }

    @Test
    void aToolCallBeforeAnyTextGetsAParentMessageWithoutTextEvents() {
        List<AgUiEvent> events =
                run(
                        step("tool_call", "id", 1, "tool", "fetch_web_page", "args", "{}"),
                        step(
                                "tool_result",
                                "id",
                                1,
                                "tool",
                                "fetch_web_page",
                                "preview",
                                "ok",
                                "ms",
                                5),
                        "Done.");

        assertEquals(
                List.of(
                        "RUN_STARTED",
                        "STATE_SNAPSHOT",
                        "TOOL_CALL_START",
                        "TOOL_CALL_ARGS",
                        "TOOL_CALL_END",
                        "TOOL_CALL_RESULT",
                        "TEXT_MESSAGE_START",
                        "TEXT_MESSAGE_CONTENT",
                        "TEXT_MESSAGE_END",
                        "RUN_FINISHED"),
                types(events));
        assertNotNull(events.get(2).parentMessageId());
        assertEveryIdPairs(events);
    }

    @Test
    void emptyTokensAreDroppedBecauseDeltaMustBeNonEmpty() {
        List<AgUiEvent> events = run("", "a", "", "");

        assertEquals(
                List.of(
                        "RUN_STARTED",
                        "STATE_SNAPSHOT",
                        "TEXT_MESSAGE_START",
                        "TEXT_MESSAGE_CONTENT",
                        "TEXT_MESSAGE_END",
                        "RUN_FINISHED"),
                types(events));
    }

    @Test
    void anEmptyRunStillHasBothBookends() {
        List<AgUiEvent> events = run();

        assertEquals(List.of("RUN_STARTED", "STATE_SNAPSHOT", "RUN_FINISHED"), types(events));
    }

    @Test
    void aStreamErrorBecomesASingleRunErrorAndNeverARunFinished() {
        List<AgUiEvent> events =
                mapper.map(
                                Flux.concat(
                                        Flux.just("partial"),
                                        Flux.error(new IllegalStateException("provider exploded"))),
                                "thread-1",
                                "run-1",
                                AGENT_ID)
                        .collectList()
                        .block();

        assertEquals(
                List.of(
                        "RUN_STARTED",
                        "STATE_SNAPSHOT",
                        "TEXT_MESSAGE_START",
                        "TEXT_MESSAGE_CONTENT",
                        "TEXT_MESSAGE_END",
                        "RUN_ERROR"),
                types(events));
        assertEveryIdPairs(events);
        AgUiEvent error = events.get(events.size() - 1);
        assertEquals(AgUiEventMapper.AGENT_ERROR_CODE, error.code());
        // Consumer-safe text only - same rule as the chat endpoint's marker.
        assertFalse(error.message().contains("provider exploded"));
        assertFalse(types(events).contains("RUN_FINISHED"));
    }

    @Test
    void theChatEndpointsInBandErrorMarkerIsAlsoUnderstood() {
        List<AgUiEvent> events =
                run(
                        "Hi",
                        AgentController.STREAM_ERROR_MARKER
                                + "This model is rate limited right now.",
                        "never seen");

        assertEquals(
                List.of(
                        "RUN_STARTED",
                        "STATE_SNAPSHOT",
                        "TEXT_MESSAGE_START",
                        "TEXT_MESSAGE_CONTENT",
                        "TEXT_MESSAGE_END",
                        "RUN_ERROR"),
                types(events));
        assertEquals("This model is rate limited right now.", events.get(5).message());
    }

    @Test
    void aToolErrorIsAResultNotARunError() {
        List<AgUiEvent> events =
                run(
                        step("tool_call", "id", 7, "tool", "run_code", "args", "{}"),
                        step(
                                "error",
                                "id",
                                7,
                                "tool",
                                "run_code",
                                "message",
                                "sandbox timed out",
                                "ms",
                                900),
                        "Fell back to an estimate.");

        assertEquals(
                List.of(
                        "RUN_STARTED",
                        "STATE_SNAPSHOT",
                        "TOOL_CALL_START",
                        "TOOL_CALL_ARGS",
                        "TOOL_CALL_END",
                        "TOOL_CALL_RESULT",
                        "TEXT_MESSAGE_START",
                        "TEXT_MESSAGE_CONTENT",
                        "TEXT_MESSAGE_END",
                        "RUN_FINISHED"),
                types(events));
        assertEquals(events.get(2).toolCallId(), events.get(5).toolCallId());
        assertEquals("Error: sandbox timed out", events.get(5).content());
    }

    @Test
    void runLevelVocabularyMapsToCustomAndInternalBookendsAreSwallowed() {
        List<AgUiEvent> events =
                run(
                        step(
                                "run_started",
                                "sessionId",
                                "thread-1",
                                "agentId",
                                AGENT_ID.toString()),
                        step("plan", "text", "1. look up 2. answer"),
                        step(
                                "handoff",
                                "fromAgent",
                                AGENT_ID.toString(),
                                "toAgent",
                                "99999999-0000-0000-0000-000000000000"),
                        step("guardrail", "tool", "generate_image", "reason", "flagged content"),
                        "ok",
                        step("run_finished", "ms", 1234));

        assertEquals(
                List.of(
                        "RUN_STARTED",
                        "STATE_SNAPSHOT",
                        "CUSTOM",
                        "CUSTOM",
                        "CUSTOM",
                        "TEXT_MESSAGE_START",
                        "TEXT_MESSAGE_CONTENT",
                        "TEXT_MESSAGE_END",
                        "RUN_FINISHED"),
                types(events));
        assertEquals("plan", events.get(2).name());
        assertEquals("1. look up 2. answer", events.get(2).value().get("text"));
        assertEquals("handoff", events.get(3).name());
        assertEquals(AGENT_ID.toString(), events.get(3).value().get("fromAgent"));
        assertEquals("guardrail", events.get(4).name());
        assertEquals("generate_image", events.get(4).value().get("tool"));
        assertEquals("flagged content", events.get(4).value().get("reason"));
        // Exactly one RUN_STARTED and one RUN_FINISHED despite the internal pair.
        assertEquals(1, types(events).stream().filter("RUN_STARTED"::equals).count());
        assertEquals(1, types(events).stream().filter("RUN_FINISHED"::equals).count());
    }

    @Test
    void anUnparseableStepEventIsDroppedNotRenderedAsText() {
        List<AgUiEvent> events = run(MARKER + "{not json", "fine");

        assertEquals(
                List.of(
                        "RUN_STARTED",
                        "STATE_SNAPSHOT",
                        "TEXT_MESSAGE_START",
                        "TEXT_MESSAGE_CONTENT",
                        "TEXT_MESSAGE_END",
                        "RUN_FINISHED"),
                types(events));
        assertEquals("fine", events.get(3).delta());
    }

    /** The wire JSON is single-line, camelCase, upper-snake type, and omits every unset field. */
    @Test
    void wireJsonIsCamelCaseSingleLineAndOmitsNulls() throws Exception {
        AgUiEvent content = AgUiEvent.textMessageContent("m1", "hi", 5L);
        String json = content.toJson();

        assertFalse(json.contains("\n"));
        JsonNode node = JSON.readTree(json);
        assertEquals("TEXT_MESSAGE_CONTENT", node.get("type").asText());
        assertEquals("m1", node.get("messageId").asText());
        assertEquals("hi", node.get("delta").asText());
        assertEquals(5L, node.get("timestamp").asLong());
        assertNull(node.get("toolCallId"));
        assertNull(node.get("threadId"));
        assertEquals(4, node.size(), json);

        JsonNode start = JSON.readTree(AgUiEvent.toolCallStart("t1", "search", "m1", 5L).toJson());
        assertEquals("t1", start.get("toolCallId").asText());
        assertEquals("search", start.get("toolCallName").asText());
        assertEquals("m1", start.get("parentMessageId").asText());
    }

    @Test
    void eachSubscriptionGetsFreshState() {
        Flux<AgUiEvent> events = mapper.map(Flux.just("a"), "thread-1", "run-1", AGENT_ID);

        List<String> first = types(events.collectList().block());
        List<String> second = types(events.collectList().block());

        assertEquals(first, second);
        assertEquals(
                List.of(
                        "RUN_STARTED",
                        "STATE_SNAPSHOT",
                        "TEXT_MESSAGE_START",
                        "TEXT_MESSAGE_CONTENT",
                        "TEXT_MESSAGE_END",
                        "RUN_FINISHED"),
                second);
    }

    /**
     * Every TEXT_MESSAGE_START has exactly one TEXT_MESSAGE_END with the same id, every
     * TOOL_CALL_START has exactly one TOOL_CALL_END with the same id, and no content/args event
     * references an id that was never started.
     */
    private static void assertEveryIdPairs(List<AgUiEvent> events) {
        Map<String, Integer> openMessages = new HashMap<>();
        Map<String, Integer> openToolCalls = new HashMap<>();
        for (AgUiEvent event : events) {
            switch (event.type()) {
                case "TEXT_MESSAGE_START" ->
                        assertNull(openMessages.put(event.messageId(), 1), "message started twice");
                case "TEXT_MESSAGE_CONTENT" ->
                        assertTrue(
                                openMessages.containsKey(event.messageId()),
                                "content for an unknown message");
                case "TEXT_MESSAGE_END" ->
                        assertEquals(
                                Integer.valueOf(1),
                                openMessages.remove(event.messageId()),
                                "end for an unknown message");
                case "TOOL_CALL_START" ->
                        assertNull(
                                openToolCalls.put(event.toolCallId(), 1),
                                "tool call started twice");
                case "TOOL_CALL_ARGS" ->
                        assertTrue(
                                openToolCalls.containsKey(event.toolCallId()),
                                "args for an unknown tool call");
                case "TOOL_CALL_END" ->
                        assertEquals(
                                Integer.valueOf(1),
                                openToolCalls.remove(event.toolCallId()),
                                "end for an unknown tool call");
                default -> {}
            }
        }
        assertTrue(openMessages.isEmpty(), "unclosed messages: " + openMessages.keySet());
        assertTrue(openToolCalls.isEmpty(), "unclosed tool calls: " + openToolCalls.keySet());
    }
}
