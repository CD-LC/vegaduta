package com.agenticai.core.agents.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The wire shape of the five turn-lifecycle step events, and that a turn without step events sends none. */
class TurnLifecycleStepEventsTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static List<JsonNode> sent(AgentStepEventEmitter emitter) throws Exception {
        emitter.complete();
        List<JsonNode> out = new ArrayList<>();
        for (String chunk : emitter.asFlux().collectList().block()) {
            assertTrue(chunk.startsWith(AgentStepEventEmitter.STEP_EVENT_MARKER), chunk);
            assertFalse(chunk.contains("\n"), "one line, or the SSE writer splits it: " + chunk);
            out.add(JSON.readTree(chunk.substring(AgentStepEventEmitter.STEP_EVENT_MARKER.length())));
        }
        return out;
    }

    private static List<String> fieldNames(JsonNode event) {
        List<String> names = new ArrayList<>();
        Iterator<String> it = event.fieldNames();
        it.forEachRemaining(names::add);
        return names;
    }

    @Test
    void finishCarriesTheReasonAndOnlyWhenThereIsOneTheProvidersWording() throws Exception {
        AgentStepEventEmitter emitter = AgentStepEventEmitter.active();
        TurnLifecycleStepEvents.on(emitter).finish("length", null);
        TurnLifecycleStepEvents.on(emitter).finish("safety", "content_filter");

        List<JsonNode> events = sent(emitter);

        assertEquals(List.of("type", "finishReason"), fieldNames(events.get(0)));
        assertEquals("finish", events.get(0).path("type").asText());
        assertEquals("length", events.get(0).path("finishReason").asText());
        assertEquals(List.of("type", "finishReason", "detail"), fieldNames(events.get(1)));
        assertEquals("content_filter", events.get(1).path("detail").asText());
    }

    @Test
    void retryFailoverContinuationAndApprovalRequiredHaveTheDocumentedFields() throws Exception {
        AgentStepEventEmitter emitter = AgentStepEventEmitter.active();
        TurnLifecycleStepEvents.Sender send = TurnLifecycleStepEvents.on(emitter);
        send.retry(2, 3, "rate_limited");
        send.failover("gpt-4o-mini", "timeout");
        send.failover(null, "provider_error");
        send.continuation(1, 2, "cut_off");
        send.approvalRequired("send_email", "c0ffee00-0000-4000-8000-000000000001");

        List<JsonNode> e = sent(emitter);

        assertEquals(List.of("type", "attempt", "maxAttempts", "reason"), fieldNames(e.get(0)));
        assertEquals(2, e.get(0).path("attempt").asInt());
        assertEquals(3, e.get(0).path("maxAttempts").asInt());
        assertEquals("rate_limited", e.get(0).path("reason").asText());
        assertEquals(List.of("type", "toModel", "reason"), fieldNames(e.get(1)));
        assertEquals("gpt-4o-mini", e.get(1).path("toModel").asText());
        assertEquals(List.of("type", "reason"), fieldNames(e.get(2)), "a model not known by name is left out, not null");
        assertEquals(List.of("type", "pass", "maxPasses", "reason"), fieldNames(e.get(3)));
        assertEquals(1, e.get(3).path("pass").asInt());
        assertEquals(2, e.get(3).path("maxPasses").asInt());
        assertEquals("cut_off", e.get(3).path("reason").asText());
        assertEquals(List.of("type", "tool", "approvalId"), fieldNames(e.get(4)));
        assertEquals("send_email", e.get(4).path("tool").asText());
        assertEquals("c0ffee00-0000-4000-8000-000000000001", e.get(4).path("approvalId").asText());
    }

    @Test
    void theTypeNamesAreTheOnesTheConstantsList() throws Exception {
        AgentStepEventEmitter emitter = AgentStepEventEmitter.active();
        TurnLifecycleStepEvents.Sender send = TurnLifecycleStepEvents.on(emitter);
        send.finish("stop", null);
        send.retry(2, 2, "x");
        send.failover("m", "x");
        send.continuation(1, 1, "approval");
        send.approvalRequired("t", "id");

        List<String> types = new ArrayList<>();
        sent(emitter).forEach(e -> types.add(e.path("type").asText()));

        assertEquals(TurnLifecycleStepEvents.TYPES, types);
        assertEquals(
                List.of("stop", "length", "safety", "malformed_tool_call", "empty", "interrupted"),
                TurnLifecycleStepEvents.FINISH_REASONS);
        assertEquals(List.of("cut_off", "unfinished_step", "approval"), TurnLifecycleStepEvents.CONTINUATION_REASONS);
    }

    @Test
    void aTurnThatDidNotAskForStepEventsGetsNoneOfThem() {
        AgentStepEventEmitter disabled = AgentStepEventEmitter.disabled();
        TurnLifecycleStepEvents.Sender send = TurnLifecycleStepEvents.on(disabled);
        send.finish("length", "x");
        send.retry(2, 3, "x");
        send.failover("m", "x");
        send.continuation(1, 2, "cut_off");
        send.approvalRequired("t", "id");
        TurnLifecycleStepEvents.on(null).finish("stop", null);

        assertFalse(disabled.hasEmitted());
        assertTrue(disabled.asFlux().collectList().block().isEmpty());
    }

    @Test
    void theEmitterStillSendsNothingAfterTheSinkIsCompleted() {
        AgentStepEventEmitter emitter = AgentStepEventEmitter.active();
        emitter.complete();

        TurnLifecycleStepEvents.on(emitter).finish("stop", null);

        assertFalse(emitter.hasEmitted(), "a late event is dropped, never thrown");
    }
}
