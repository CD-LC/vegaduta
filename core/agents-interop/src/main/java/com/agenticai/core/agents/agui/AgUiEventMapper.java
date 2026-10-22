package com.agenticai.core.agents.agui;

import com.agenticai.core.agents.core.AgentController;
import com.agenticai.core.agents.tools.AgentStepEventEmitter;
import com.agenticai.core.agents.turn.AgentChatStreamResponder;
import com.agenticai.core.json.JsonMappers;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Maps the platform's chat stream - plain text tokens interleaved with single-line {@link
 * AgentStepEventEmitter#STEP_EVENT_MARKER}-prefixed step events, exactly what {@code
 * AgentController#chat} puts on the wire when {@code ChatRequest.stepEvents} is true - onto the
 * ordered AG-UI event sequence a protocol client expects. Pure: a {@code Flux<String>} in, a {@code
 * Flux<AgUiEvent>} out, no Spring, no I/O, unit-tested with literal chunk fixtures ({@code
 * AgUiEventMapperTest}).
 *
 * <h2>The sequence it guarantees</h2>
 *
 * <pre>
 * RUN_STARTED{threadId,runId}
 * STATE_SNAPSHOT{snapshot:{sessionId,agentId}}
 *   TEXT_MESSAGE_START{messageId,role:"assistant"}     - lazily, on the first non-empty token
 *   TEXT_MESSAGE_CONTENT{messageId,delta}              - one per non-empty token
 *   TEXT_MESSAGE_END{messageId}                        - when a tool call interrupts the text, or at the end
 *   TOOL_CALL_START{toolCallId,toolCallName,parentMessageId}
 *   TOOL_CALL_ARGS{toolCallId,delta}                   - the whole (already-redacted) argument JSON as one delta
 *   TOOL_CALL_END{toolCallId}
 *   TOOL_CALL_RESULT{messageId,toolCallId,content,role:"tool"}
 *   CUSTOM{name:"plan"|"guardrail"|"handoff",value:{...}}
 * RUN_FINISHED{threadId,runId}   - or -   RUN_ERROR{message,code}, never both
 * </pre>
 *
 * Text after a tool call opens a NEW assistant message with a fresh id: the protocol's text message
 * is a contiguous run of deltas, and a tool call in the middle is by definition a break in it.
 * Every id pairs (start/end for messages and tool calls) and the run's terminal event is always
 * last.
 *
 * <h2>How each input chunk is classified</h2>
 *
 * <ul>
 *   <li>Starts with {@code STEP_EVENT_MARKER}: JSON step event. {@code tool_call} / {@code
 *       tool_result} / {@code error} are the tool vocabulary; {@code plan} / {@code guardrail} /
 *       {@code handoff} become {@code CUSTOM}; {@code run_started} / {@code run_finished} are
 *       swallowed because this mapper emits the protocol's own lifecycle bookends from the stream's
 *       subscribe/complete signals, and a second pair would violate the "exactly one RUN_STARTED"
 *       rule.
 *   <li>Starts with {@code AgentController#STREAM_ERROR_MARKER}: the chat endpoint's in-band
 *       mid-stream failure. Mapped to {@code RUN_ERROR} and the stream is ended there - the
 *       controller feeds this mapper the raw service Flux (where a failure is a real {@code
 *       onError}), but handling the marker too means the mapper is correct over the chat endpoint's
 *       OUTPUT as well, which is the cheaper thing to test against and the shape any future proxy
 *       would see.
 *   <li>Anything else: a text token. Empty tokens are dropped - the protocol requires {@code delta}
 *       to be non-empty.
 * </ul>
 *
 * <h2>Why a step {@code error} is a TOOL_CALL_RESULT, not a RUN_ERROR</h2>
 *
 * The emitter's {@code error} means "this tool threw" (see {@code
 * AgentStepEventEmitter#toolError}). Whether that ends the run is decided downstream of the tool -
 * provider fallback and the hosted chain can retry the whole turn, and then tokens keep flowing.
 * Emitting {@code RUN_ERROR} on the tool event and then more text would be a protocol violation; a
 * result whose content says the tool failed is exactly what the model itself was shown, and if the
 * run really does die the Flux error that follows becomes the one {@code RUN_ERROR}.
 *
 * <h2>Malformed step events</h2>
 *
 * A marker-prefixed chunk whose JSON does not parse is logged at DEBUG and dropped - never rendered
 * as text (that would print framing junk into the user's reply, the exact failure the marker exists
 * to prevent) and never fatal (fail-open, same as the emitter's own posture).
 */
public final class AgUiEventMapper {

    private static final Logger log = LoggerFactory.getLogger(AgUiEventMapper.class);

    /**
     * {@code code} on a RUN_ERROR that came from the agent turn itself (as opposed to transport).
     */
    public static final String AGENT_ERROR_CODE = "AGENT_ERROR";

    private static final ObjectMapper MAPPER = JsonMappers.plain();

    private final Supplier<String> ids;
    private final LongSupplier clock;

    /** Production wiring: random UUID ids, wall-clock timestamps. */
    public AgUiEventMapper() {
        this(() -> UUID.randomUUID().toString(), System::currentTimeMillis);
    }

    /**
     * Test seam - deterministic ids and timestamps so a golden test can assert the exact event
     * list, not just its shape.
     */
    public AgUiEventMapper(Supplier<String> ids, LongSupplier clock) {
        this.ids = ids;
        this.clock = clock;
    }

    /**
     * @param chunks the chat stream (tokens + marker-prefixed step events)
     * @param threadId echoed on RUN_STARTED/RUN_FINISHED; also the state snapshot's {@code
     *     sessionId}
     * @param runId echoed on RUN_STARTED/RUN_FINISHED
     * @param agentId the agent running, for the state snapshot (null-tolerant)
     */
    public Flux<AgUiEvent> map(Flux<String> chunks, String threadId, String runId, UUID agentId) {
        // Per-subscription state via defer: a Flux may be subscribed more than
        // once (a retry, a test), and message/tool-call bookkeeping must not
        // bleed from one subscription into the next.
        return Flux.defer(
                () -> {
                    RunState state = new RunState();
                    Flux<AgUiEvent> opening =
                            Flux.fromIterable(
                                    List.of(
                                            AgUiEvent.runStarted(
                                                    threadId, runId, clock.getAsLong()),
                                            AgUiEvent.stateSnapshot(
                                                    snapshot(threadId, agentId),
                                                    clock.getAsLong())));
                    Flux<AgUiEvent> body =
                            chunks
                                    // concatMap (not flatMap): each chunk expands to 0..n events
                                    // that must stay in order relative to neighbouring chunks.
                                    .concatMap(chunk -> Flux.fromIterable(state.accept(chunk)))
                                    .concatWith(
                                            Flux.defer(
                                                    () ->
                                                            Flux.fromIterable(
                                                                    state.finish(threadId, runId))))
                                    // A failure after RUN_STARTED can only be reported in-band -
                                    // the status line is long committed. Close whatever message
                                    // was open so ids still pair, then one RUN_ERROR, then done;
                                    // finish() is NOT called on this path so RUN_FINISHED never
                                    // follows a RUN_ERROR.
                                    .onErrorResume(
                                            e ->
                                                    Flux.fromIterable(
                                                            state.abort(
                                                                    AgentChatStreamResponder
                                                                            .midStreamFailureMessage(
                                                                                    e))));
                    return opening.concatWith(body);
                });
    }

    private static Map<String, Object> snapshot(String threadId, UUID agentId) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("sessionId", threadId);
        snapshot.put("agentId", agentId == null ? null : agentId.toString());
        return snapshot;
    }

    /**
     * Mutable bookkeeping for one subscription: which assistant message is open (if any), the last
     * message id (parent for a tool call that arrives with no text open), and the mapping from the
     * emitter's small integer step ids to the protocol's string tool-call ids.
     */
    private final class RunState {
        /** Id of the assistant message currently receiving deltas, or null between messages. */
        private String openMessageId;

        /** Most recent assistant message id, open or closed - TOOL_CALL_START's parentMessageId. */
        private String lastMessageId;

        /**
         * Emitter step id -> protocol toolCallId. Tool results reference the call by the same step
         * id.
         */
        private final Map<Integer, String> toolCallIds = new LinkedHashMap<>();

        /**
         * Set once the stream has terminated (error marker seen), so trailing chunks are ignored.
         */
        private boolean terminated;

        List<AgUiEvent> accept(String chunk) {
            List<AgUiEvent> out = new ArrayList<>(4);
            if (terminated || chunk == null) {
                return out;
            }
            if (chunk.startsWith(AgentStepEventEmitter.STEP_EVENT_MARKER)) {
                acceptStepEvent(
                        chunk.substring(AgentStepEventEmitter.STEP_EVENT_MARKER.length()), out);
                return out;
            }
            if (chunk.startsWith(AgentController.STREAM_ERROR_MARKER)) {
                out.addAll(abort(chunk.substring(AgentController.STREAM_ERROR_MARKER.length())));
                return out;
            }
            if (chunk.isEmpty()) {
                return out;
            }
            if (openMessageId == null) {
                openMessageId = ids.get();
                lastMessageId = openMessageId;
                out.add(AgUiEvent.textMessageStart(openMessageId, clock.getAsLong()));
            }
            out.add(AgUiEvent.textMessageContent(openMessageId, chunk, clock.getAsLong()));
            return out;
        }

        private void acceptStepEvent(String json, List<AgUiEvent> out) {
            JsonNode event;
            try {
                event = MAPPER.readTree(json);
            } catch (Exception e) {
                log.debug("Dropping an unparseable step event on the AG-UI stream", e);
                return;
            }
            if (event == null || !event.isObject()) {
                return;
            }
            String type = event.path("type").asString("");
            switch (type) {
                case "tool_call" -> {
                    // A tool call breaks the assistant's text run - close it so
                    // the deltas that follow the result open a fresh message.
                    closeOpenMessage(out);
                    int stepId = event.path("id").asInt();
                    String toolCallId = ids.get();
                    toolCallIds.put(stepId, toolCallId);
                    if (lastMessageId == null) {
                        // No text preceded this call: the tool call still needs
                        // an assistant message to hang off. Mint the id without
                        // a TEXT_MESSAGE_START - a message consisting solely of
                        // tool calls has no text events in the protocol.
                        lastMessageId = ids.get();
                    }
                    long now = clock.getAsLong();
                    out.add(
                            AgUiEvent.toolCallStart(
                                    toolCallId,
                                    event.path("tool").asString(""),
                                    lastMessageId,
                                    now));
                    out.add(
                            AgUiEvent.toolCallArgs(
                                    toolCallId, event.path("args").asString(""), now));
                    out.add(AgUiEvent.toolCallEnd(toolCallId, now));
                }
                case "tool_result" ->
                        out.add(
                                AgUiEvent.toolCallResult(
                                        ids.get(),
                                        toolCallIdFor(event),
                                        event.path("preview").asString(""),
                                        clock.getAsLong()));
                case "error" ->
                        out.add(
                                AgUiEvent.toolCallResult(
                                        ids.get(),
                                        toolCallIdFor(event),
                                        "Error: " + event.path("message").asString(""),
                                        clock.getAsLong()));
                case "plan" ->
                        out.add(AgUiEvent.custom("plan", fields(event, "text"), clock.getAsLong()));
                case "guardrail" ->
                        out.add(
                                AgUiEvent.custom(
                                        "guardrail",
                                        fields(event, "tool", "reason"),
                                        clock.getAsLong()));
                case "handoff" ->
                        out.add(
                                AgUiEvent.custom(
                                        "handoff",
                                        fields(event, "fromAgent", "toAgent"),
                                        clock.getAsLong()));
                // The protocol's own lifecycle bookends come from the stream's
                // subscribe/complete signals (see the class javadoc); a second
                // pair here would be a violation, so these are swallowed.
                case "run_started", "run_finished" -> {}
                default ->
                        log.debug(
                                "Ignoring step event of unknown type '{}' on the AG-UI stream",
                                type);
            }
        }

        /**
         * The protocol tool-call id for a result/error's step id. A result for a call this
         * subscription never saw (a dropped tool_call event - the emitter is fail-open) gets a
         * fresh id rather than being lost: an unpaired result is more useful to a trace than no
         * result.
         */
        private String toolCallIdFor(JsonNode event) {
            return toolCallIds.computeIfAbsent(event.path("id").asInt(), id -> ids.get());
        }

        List<AgUiEvent> finish(String threadId, String runId) {
            List<AgUiEvent> out = new ArrayList<>(2);
            if (terminated) {
                return out;
            }
            closeOpenMessage(out);
            terminated = true;
            out.add(AgUiEvent.runFinished(threadId, runId, clock.getAsLong()));
            return out;
        }

        /**
         * The run's failure terminal: close any open message, emit exactly one RUN_ERROR, and
         * ignore everything after it. Idempotent - a second failure signal (a stream error arriving
         * after an in-band marker chunk) produces nothing.
         */
        List<AgUiEvent> abort(String consumerMessage) {
            List<AgUiEvent> out = new ArrayList<>(2);
            if (terminated) {
                return out;
            }
            closeOpenMessage(out);
            terminated = true;
            out.add(AgUiEvent.runError(consumerMessage, AGENT_ERROR_CODE, clock.getAsLong()));
            return out;
        }

        private void closeOpenMessage(List<AgUiEvent> out) {
            if (openMessageId != null) {
                out.add(AgUiEvent.textMessageEnd(openMessageId, clock.getAsLong()));
                openMessageId = null;
            }
        }
    }

    /** The named fields of a step event as an insertion-ordered map, missing ones omitted. */
    private static Map<String, Object> fields(JsonNode event, String... names) {
        Map<String, Object> value = new LinkedHashMap<>();
        for (String name : names) {
            JsonNode field = event.get(name);
            if (field != null && !field.isNull()) {
                value.put(name, field.isString() ? field.asString() : field.toString());
            }
        }
        return value;
    }
}
