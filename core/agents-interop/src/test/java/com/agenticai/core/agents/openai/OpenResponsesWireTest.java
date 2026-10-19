package com.agenticai.core.agents.openai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.agents.budget.PerUserBudgetExhaustedException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;

/**
 * Field contract of the Responses wire format. The required-field lists come from the openai-python
 * SDK models (Response, ResponseOutputMessage, ResponseOutputText, ResponseUsage and the
 * response.* event classes), which the SDK validates on receipt, so a missing field here would make
 * an unmodified OpenAI client fail to parse our reply.
 */
class OpenResponsesWireTest {

    private static final List<String> RESPONSE_REQUIRED = List.of(
        "id", "created_at", "object", "output", "parallel_tool_calls", "tool_choice", "tools", "model",
        "status", "error", "usage", "metadata");

    private final ObjectMapper mapper = new ObjectMapper();

    private OpenResponsesWire wire(String instructions, JsonNode metadata) {
        return new OpenResponsesWire(mapper,
            new OpenResponsesWire.Meta("resp_1", "msg_1", 1700000000L, "agent-x", instructions, metadata));
    }

    private void assertHasAll(JsonNode node, List<String> fields) {
        for (String f : fields) {
            assertTrue(node.has(f), "missing '" + f + "' in " + node);
        }
    }

    private List<JsonNode> run(OpenResponsesWire w, Flux<String> tokens) throws Exception {
        List<ServerSentEvent<String>> frames = w.events(tokens, 10).collectList().block(Duration.ofSeconds(5));
        List<JsonNode> out = new ArrayList<>();
        for (ServerSentEvent<String> f : frames) {
            if (!"[DONE]".equals(f.data())) {
                out.add(mapper.readTree(f.data()));
            }
        }
        assertEquals("[DONE]", frames.get(frames.size() - 1).data());
        return out;
    }

    @Test
    void completedResponseCarriesEveryFieldAnSdkValidates() {
        JsonNode r = wire("be brief", mapper.createObjectNode().put("k", "v"))
            .response("completed", "hello", 7L, 2L, 1700000005L, null);
        assertHasAll(r, RESPONSE_REQUIRED);
        assertEquals("response", r.get("object").asText());
        assertEquals("completed", r.get("status").asText());
        assertTrue(r.get("error").isNull());
        assertEquals("be brief", r.get("instructions").asText());
        assertEquals("v", r.get("metadata").get("k").asText());
        JsonNode item = r.get("output").get(0);
        assertHasAll(item, List.of("id", "type", "status", "role", "content"));
        assertEquals("message", item.get("type").asText());
        assertEquals("assistant", item.get("role").asText());
        JsonNode part = item.get("content").get(0);
        assertHasAll(part, List.of("type", "text", "annotations"));
        assertEquals("output_text", part.get("type").asText());
        assertEquals(0, part.get("annotations").size());
        JsonNode usage = r.get("usage");
        assertHasAll(usage, List.of("input_tokens", "input_tokens_details", "output_tokens",
            "output_tokens_details", "total_tokens"));
        assertEquals(9, usage.get("total_tokens").asInt());
    }

    @Test
    void requestedSamplingIsReportedAsNotAppliedNeverEchoedAsApplied() {
        JsonNode r = wire(null, null).response("completed", "x", 1L, 1L, 1L, null);
        assertTrue(r.get("temperature").isNull());
        assertTrue(r.get("max_output_tokens").isNull());
        assertTrue(r.get("instructions").isNull());
        assertTrue(r.get("metadata").isObject());
        assertEquals(0, r.get("metadata").size());
    }

    @Test
    void inProgressResponseHasNoOutputAndNoUsage() {
        JsonNode r = wire(null, null).response("in_progress", null, null, null, null, null);
        assertEquals(0, r.get("output").size());
        assertTrue(r.get("usage").isNull());
        assertTrue(r.get("completed_at").isNull());
    }

    @Test
    void streamingEventsFollowTheDocumentedOrderWithMonotonicSequenceNumbers() throws Exception {
        List<JsonNode> events = run(wire(null, null), Flux.just("a", "", "bc", "d"));
        List<String> types = events.stream().map(e -> e.get("type").asText()).toList();
        assertEquals(List.of(
            "response.created", "response.in_progress", "response.output_item.added", "response.content_part.added",
            "response.output_text.delta", "response.output_text.delta", "response.output_text.delta",
            "response.output_text.done", "response.content_part.done", "response.output_item.done",
            "response.completed"), types);
        for (int i = 0; i < events.size(); i++) {
            assertEquals(i, events.get(i).get("sequence_number").asInt(), "sequence at " + i);
        }
    }

    @Test
    void streamingEventPayloadsCarryTheirRequiredFields() throws Exception {
        List<JsonNode> e = run(wire(null, null), Flux.just("Hi"));
        assertHasAll(e.get(0).get("response"), RESPONSE_REQUIRED);
        assertEquals("in_progress", e.get(0).get("response").get("status").asText());
        assertHasAll(e.get(2), List.of("output_index", "item"));
        assertEquals("in_progress", e.get(2).get("item").get("status").asText());
        assertEquals(0, e.get(2).get("item").get("content").size());
        assertHasAll(e.get(3), List.of("item_id", "output_index", "content_index", "part"));
        assertEquals("", e.get(3).get("part").get("text").asText());
        JsonNode delta = e.get(4);
        assertHasAll(delta, List.of("item_id", "output_index", "content_index", "delta", "logprobs"));
        assertEquals("Hi", delta.get("delta").asText());
        assertEquals("msg_1", delta.get("item_id").asText());
        assertHasAll(e.get(5), List.of("item_id", "output_index", "content_index", "text", "logprobs"));
        assertEquals("Hi", e.get(5).get("text").asText());
        assertHasAll(e.get(6), List.of("item_id", "output_index", "content_index", "part"));
        assertEquals("Hi", e.get(6).get("part").get("text").asText());
        assertEquals("completed", e.get(7).get("item").get("status").asText());
        JsonNode completed = e.get(8).get("response");
        assertEquals("completed", completed.get("status").asText());
        assertEquals("Hi", completed.get("output").get(0).get("content").get(0).get("text").asText());
        assertEquals(10, completed.get("usage").get("input_tokens").asInt());
        assertEquals(1, completed.get("usage").get("output_tokens").asInt());
        assertTrue(completed.get("completed_at").asLong() > 0);
    }

    @Test
    void eachFrameIsSentAsANamedSseEventMatchingItsPayloadType() throws Exception {
        List<ServerSentEvent<String>> frames = wire(null, null).events(Flux.just("x"), 1)
            .collectList().block(Duration.ofSeconds(5));
        for (ServerSentEvent<String> f : frames.subList(0, frames.size() - 1)) {
            assertEquals(mapper.readTree(f.data()).get("type").asText(), f.event());
        }
        assertNull(frames.get(frames.size() - 1).event());
    }

    @Test
    void failureAfterTheStreamOpenedIsErrorThenResponseFailedWithAnOpenAiCodeAndNoRawText() throws Exception {
        Flux<String> tokens = Flux.concat(Flux.just("par"), Flux.error(new PerUserBudgetExhaustedException(
            "Daily text budget reached.", "text", BigDecimal.ONE, BigDecimal.ONE, 60L)));
        List<JsonNode> e = run(wire(null, null), tokens);
        int n = e.size();
        assertEquals("error", e.get(n - 2).get("type").asText());
        assertEquals("insufficient_quota", e.get(n - 2).get("code").asText());
        assertTrue(e.get(n - 2).has("message"));
        assertTrue(e.get(n - 2).get("param").isNull());
        JsonNode failed = e.get(n - 1);
        assertEquals("response.failed", failed.get("type").asText());
        assertEquals("failed", failed.get("response").get("status").asText());
        assertEquals("insufficient_quota", failed.get("response").get("error").get("code").asText());
        assertEquals(0, failed.get("response").get("output").size());
        assertFalse(e.stream().anyMatch(x -> "response.completed".equals(x.get("type").asText())));
        for (int i = 0; i < n; i++) {
            assertEquals(i, e.get(i).get("sequence_number").asInt());
        }
    }

    @Test
    void unclassifiedFailureUsesTheServerErrorCode() throws Exception {
        List<JsonNode> e = run(wire(null, null), Flux.error(new IllegalStateException("secret internals")));
        JsonNode failed = e.get(e.size() - 1);
        assertEquals("server_error", failed.get("response").get("error").get("code").asText());
        assertFalse(failed.toString().contains("secret"));
    }
}
