package com.agenticai.core.agents.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;

/**
 * Builds the Open Responses / OpenAI Responses JSON: the {@code response} object and the
 * streaming events. Pure (no Spring, no I/O) so the shapes are unit-testable against recorded
 * fixtures. One instance per response: it owns the event {@code sequence_number} counter.
 *
 * <p>Shapes follow the OpenAI Responses API reference (the Open Responses spec derives from it):
 * https://platform.openai.com/docs/api-reference/responses/object and
 * https://platform.openai.com/docs/api-reference/responses-streaming, cross-checked against the
 * openai-python SDK models (https://github.com/openai/openai-python/tree/main/src/openai/types/responses)
 * and https://www.openresponses.org/specification. Every {@code response.*} event carries
 * {@code type} and a {@code sequence_number} that starts at 0 and increases by one; each is sent as
 * {@code event: <type>} + {@code data: <json>}, and the stream ends with {@code data: [DONE]}.
 *
 * <p>Event order for one text message: {@code response.created}, {@code response.in_progress},
 * {@code response.output_item.added}, {@code response.content_part.added}, N x
 * {@code response.output_text.delta}, {@code response.output_text.done},
 * {@code response.content_part.done}, {@code response.output_item.done}, {@code response.completed}.
 * A failure after the stream opened is {@code error} then {@code response.failed}.
 */
final class OpenResponsesWire {

    /** What a response echoes about its request and identity. */
    record Meta(
        String responseId, String itemId, long createdAt, String model, String instructions, JsonNode metadata
    ) {}

    private final ObjectMapper mapper;
    private final Meta meta;
    private final AtomicInteger sequence = new AtomicInteger();

    OpenResponsesWire(ObjectMapper mapper, Meta meta) {
        this.mapper = mapper;
        this.meta = meta;
    }

    // ------------------------------------------------------------ response object

    /**
     * The {@code response} object. {@code text == null} means no output item yet ({@code output: []}).
     * {@code temperature}/{@code max_output_tokens} are reported as null because the agent owns its
     * sampling and the request values are not applied.
     */
    ObjectNode response(
        String status, String text, Long inputTokens, Long outputTokens, Long completedAt, OpenAiCompatErrors.Info error
    ) {
        ObjectNode r = mapper.createObjectNode();
        r.put("id", meta.responseId());
        r.put("object", "response");
        r.put("created_at", meta.createdAt());
        if (completedAt == null) {
            r.putNull("completed_at");
        } else {
            r.put("completed_at", completedAt.longValue());
        }
        r.put("status", status);
        if (error == null) {
            r.putNull("error");
        } else {
            ObjectNode e = r.putObject("error");
            e.put("code", error.code() == null ? "server_error" : error.code());
            e.put("message", error.message() == null ? "" : error.message());
        }
        r.putNull("incomplete_details");
        if (meta.instructions() == null) {
            r.putNull("instructions");
        } else {
            r.put("instructions", meta.instructions());
        }
        r.putNull("max_output_tokens");
        r.put("model", meta.model());
        ArrayNode output = r.putArray("output");
        if (text != null) {
            output.add(messageItem("completed", text));
        }
        r.put("parallel_tool_calls", false);
        r.putNull("previous_response_id");
        r.put("store", false);
        r.putNull("temperature");
        r.put("tool_choice", "auto");
        r.putArray("tools");
        r.putNull("top_p");
        r.put("truncation", "disabled");
        r.putObject("text").putObject("format").put("type", "text");
        r.put("background", false);
        if (inputTokens == null || outputTokens == null) {
            r.putNull("usage");
        } else {
            usage(r.putObject("usage"), inputTokens, outputTokens);
        }
        r.putNull("user");
        if (meta.metadata() == null || !meta.metadata().isObject()) {
            r.putObject("metadata");
        } else {
            r.set("metadata", meta.metadata().deepCopy());
        }
        return r;
    }

    /** Usage is an estimate (about four characters per token), as on chat completions. */
    static void usage(ObjectNode usage, long inputTokens, long outputTokens) {
        usage.put("input_tokens", inputTokens);
        usage.putObject("input_tokens_details").put("cached_tokens", 0);
        usage.put("output_tokens", outputTokens);
        usage.putObject("output_tokens_details").put("reasoning_tokens", 0);
        usage.put("total_tokens", inputTokens + outputTokens);
    }

    private ObjectNode messageItem(String status, String text) {
        ObjectNode item = mapper.createObjectNode();
        item.put("id", meta.itemId());
        item.put("type", "message");
        item.put("status", status);
        item.put("role", "assistant");
        ArrayNode content = item.putArray("content");
        if (text != null) {
            content.add(outputText(text));
        }
        return item;
    }

    private ObjectNode outputText(String text) {
        ObjectNode part = mapper.createObjectNode();
        part.put("type", "output_text");
        part.put("text", text);
        part.putArray("annotations");
        return part;
    }

    // ------------------------------------------------------------ streaming events

    /**
     * The whole event stream for one turn. {@code tokens} are the agent's text chunks (empty ones
     * are skipped); {@code inputTokens} is the estimated prompt size.
     */
    Flux<ServerSentEvent<String>> events(Flux<String> tokens, long inputTokens) {
        StringBuilder text = new StringBuilder();
        Flux<ServerSentEvent<String>> body = Flux.concat(
            Flux.defer(() -> Flux.just(
                frame(created()),
                frame(inProgress()),
                frame(outputItemAdded()),
                frame(contentPartAdded()))),
            tokens
                .filter(t -> t != null && !t.isEmpty())
                .doOnNext(text::append)
                .map(t -> frame(textDelta(t))),
            Flux.defer(() -> {
                String full = text.toString();
                return Flux.just(
                    frame(textDone(full)),
                    frame(contentPartDone(full)),
                    frame(outputItemDone(full)),
                    frame(completed(full, inputTokens, OpenAiCompatCalls.estimateTokens(full.length()),
                        System.currentTimeMillis() / 1000L)));
            })
        ).onErrorResume(e -> {
            // Headers are already committed by the time a mid-stream failure lands, so it rides
            // in-band: an `error` event, then `response.failed` (never the raw exception text).
            OpenAiCompatErrors.Info info = OpenAiCompatErrors.classify(e);
            return Flux.just(frame(errorEvent(info)), frame(failed(info)));
        });
        return body.concatWith(Flux.just(ServerSentEvent.<String>builder().data("[DONE]").build()));
    }

    private ServerSentEvent<String> frame(ObjectNode event) {
        return ServerSentEvent.<String>builder().event(event.get("type").asText()).data(event.toString()).build();
    }

    private ObjectNode event(String type) {
        ObjectNode e = mapper.createObjectNode();
        e.put("type", type);
        e.put("sequence_number", sequence.getAndIncrement());
        return e;
    }

    ObjectNode created() {
        ObjectNode e = event("response.created");
        e.set("response", response("in_progress", null, null, null, null, null));
        return e;
    }

    ObjectNode inProgress() {
        ObjectNode e = event("response.in_progress");
        e.set("response", response("in_progress", null, null, null, null, null));
        return e;
    }

    ObjectNode outputItemAdded() {
        ObjectNode e = event("response.output_item.added");
        e.put("output_index", 0);
        e.set("item", messageItem("in_progress", null));
        return e;
    }

    ObjectNode contentPartAdded() {
        ObjectNode e = event("response.content_part.added");
        e.put("item_id", meta.itemId());
        e.put("output_index", 0);
        e.put("content_index", 0);
        e.set("part", outputText(""));
        return e;
    }

    ObjectNode textDelta(String delta) {
        ObjectNode e = event("response.output_text.delta");
        e.put("item_id", meta.itemId());
        e.put("output_index", 0);
        e.put("content_index", 0);
        e.put("delta", delta);
        e.putArray("logprobs");
        return e;
    }

    ObjectNode textDone(String text) {
        ObjectNode e = event("response.output_text.done");
        e.put("item_id", meta.itemId());
        e.put("output_index", 0);
        e.put("content_index", 0);
        e.put("text", text);
        e.putArray("logprobs");
        return e;
    }

    ObjectNode contentPartDone(String text) {
        ObjectNode e = event("response.content_part.done");
        e.put("item_id", meta.itemId());
        e.put("output_index", 0);
        e.put("content_index", 0);
        e.set("part", outputText(text));
        return e;
    }

    ObjectNode outputItemDone(String text) {
        ObjectNode e = event("response.output_item.done");
        e.put("output_index", 0);
        e.set("item", messageItem("completed", text));
        return e;
    }

    ObjectNode completed(String text, long inputTokens, long outputTokens, long completedAt) {
        ObjectNode e = event("response.completed");
        e.set("response", response("completed", text, inputTokens, outputTokens, completedAt, null));
        return e;
    }

    ObjectNode errorEvent(OpenAiCompatErrors.Info info) {
        ObjectNode e = event("error");
        e.put("code", info.code() == null ? "server_error" : info.code());
        e.put("message", info.message() == null ? "" : info.message());
        e.putNull("param");
        return e;
    }

    ObjectNode failed(OpenAiCompatErrors.Info info) {
        ObjectNode e = event("response.failed");
        e.set("response", response("failed", null, null, null, null, info));
        return e;
    }
}
