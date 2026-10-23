package com.agenticai.core.agents.agui;

import com.agenticai.core.agents.agui.AgUiRunEventBuffer.BufferedEvent;
import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.tools.AgentStepEventEmitter;
import com.agenticai.core.agents.tools.AgentToolPort;
import com.agenticai.core.agents.tools.ToolDescriptor;
import com.agenticai.core.agents.turn.AgentChatService;
import com.agenticai.core.agents.turn.ChatTurnRequest;
import com.agenticai.core.agents.turn.TurnContext;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.sharing.PermissionLevel;
import java.security.Principal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

/**
 * AG-UI (Agent-User Interaction Protocol) run endpoint - {@code POST
 * /api/agents/{agentId}/agui/run}. Lets any AG-UI-speaking front end ({@code
 * @ag-ui/client}'s {@code HttpAgent}, CopilotKit, a hand-rolled SSE reader)
 * drive a platform agent with zero platform-specific client code, and exposes
 * the agent's tool activity as first-class protocol events rather than the
 * chat endpoint's in-band marker chunks.
 *
 * <h2>Strictly additive over {@code AgentController#chat}</h2>
 * This endpoint does not run a different kind of turn. It builds the SAME
 * merged stream the chat endpoint builds for a {@code stepEvents:true}
 * request - {@link AgentChatService#chat} with a live {@link
 * AgentStepEventEmitter}, merged with that emitter's side channel - and hands
 * it to {@link AgUiEventMapper}. Same authorization (RUNNER, enforced here at
 * the HTTP boundary for the reason {@code AgentController#chat}'s own comment
 * gives), same session semantics ({@code threadId} IS the {@code sessionId}),
 * same moderation, guardrails, budget metering and persistence, because all of
 * that lives inside the service. Nothing about {@code /chat} changes.
 *
 * <h2>Transport</h2>
 * SSE only ({@code produces = text/event-stream}): Spring answers a request
 * whose {@code Accept} cannot take {@code text/event-stream} with 406 before
 * this handler runs, which is the protocol's own rule for the HTTP transport.
 * The protobuf transport is deliberately not offered. Each event is one
 * {@code data:<json>} frame - Spring's SSE writer adds the {@code data:} prefix
 * and the blank-line terminator; the JSON is single-line by construction (see
 * {@link AgUiEvent#toJson}). A {@code :keepalive} comment goes out every
 * {@link #HEARTBEAT} of silence so an idle proxy does not drop a long tool
 * call; SSE clients ignore comment lines by specification.
 *
 * <h2>Errors</h2>
 * Anything that fails BEFORE the turn's stream exists - unknown agent, no
 * RUNNER access (404 either way, anti-enumeration), a malformed body, a tenant
 * kill switch, an inbound guardrail 422 - is thrown synchronously from this
 * method and mapped to a normal HTTP status by {@code GlobalExceptionHandler},
 * exactly as on {@code /chat}: the AG-UI client sees an HTTP error, no events.
 * Anything that fails AFTER (provider outage mid-turn, a guardrail on tool
 * output) is a {@code RUN_ERROR} event; the mapper guarantees it is the last
 * event and that {@code RUN_FINISHED} never follows it.
 */
@RestController
@RequestMapping("/api/agents/{agentId}/agui")
public class AgUiRunController {

    /**
     * Mirrors {@code ChatRequest.message}'s {@code @Size(max = 8000)} - that cap is a DoS control
     * on the first attacker-controlled string of a turn (see the field's javadoc there), and this
     * endpoint feeds the same guardrail path, so it must not be a way around it.
     */
    static final int MAX_MESSAGE_CHARS = 8000;

    /** How long the stream may sit silent before a {@code :keepalive} comment is written. */
    static final Duration HEARTBEAT = Duration.ofSeconds(15);

    private final AgentService agentService;
    private final AgentChatService agentChatService;
    private final AgentToolPort agentToolProvider;
    private final AgUiRunEventBuffer runBuffer;
    private final AgUiEventMapper mapper = new AgUiEventMapper();

    public AgUiRunController(
            AgentService agentService,
            AgentChatService agentChatService,
            AgentToolPort agentToolProvider,
            AgUiRunEventBuffer runBuffer) {
        this.agentService = agentService;
        this.agentChatService = agentChatService;
        this.agentToolProvider = agentToolProvider;
        this.runBuffer = runBuffer;
    }

    @PostMapping(
            value = "/run",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> run(
            @PathVariable UUID agentId, @RequestBody RunAgentInput input, Principal principal) {
        // Same gate, same place, same reasoning as AgentController#chat: this
        // is an authenticated-human entry point, so RUNNER is enforced at the
        // controller and NOT inside AgentChatService#chat (which every
        // omnichannel webhook shares under a synthetic per-channel subject).
        agentService.requireAccess(agentId, PermissionLevel.RUNNER);

        String userMessage = input.lastUserMessageText();
        if (!StringUtils.hasText(userMessage)) {
            // IllegalArgumentException -> 400 via GlobalExceptionHandler.
            throw new IllegalArgumentException(
                    "messages must contain at least one user message with text content");
        }
        if (userMessage.length() > MAX_MESSAGE_CHARS) {
            throw new IllegalArgumentException(
                    "the user message is too long (" + MAX_MESSAGE_CHARS + " character maximum)");
        }

        String threadId =
                StringUtils.hasText(input.threadId())
                        ? input.threadId()
                        : UUID.randomUUID().toString();
        String runId =
                StringUtils.hasText(input.runId()) ? input.runId() : UUID.randomUUID().toString();
        if (!AgUiRunEventBuffer.RUN_ID_PATTERN.matcher(runId).matches()) {
            throw new IllegalArgumentException(
                    "runId may only contain letters, digits, '-' and '_' (128 characters maximum)");
        }
        // Long-term memory subject key - identical derivation to
        // AgentController#chat so an AG-UI client and the console share one
        // memory for the same person.
        String subjectKey =
                principal != null && StringUtils.hasText(principal.getName())
                        ? principal.getName()
                        : threadId;

        // Always a live emitter: the whole point of this endpoint is the tool
        // trace. The service's twelve-arg overload is the same one /chat uses
        // with stepEvents:true; everything else is the chat endpoint's
        // defaults (no attachments, no turnRef, no vision session, agent's
        // own reasoning depth).
        AgentStepEventEmitter stepEvents = AgentStepEventEmitter.active();
        ChatTurnRequest chatTurn =
                new ChatTurnRequest(
                        agentId,
                        threadId,
                        subjectKey,
                        userMessage,
                        null,
                        null,
                        null,
                        List.of(),
                        null,
                        null,
                        false,
                        stepEvents,
                        false);
        TurnContext turnContext =
                TurnContext.forEntry(TenantContext.current().orElse(null), chatTurn);
        Flux<String> tokens = agentChatService.chat(turnContext, chatTurn);
        // Same merge shape as AgentController#chat, for the same reason: the
        // emitter's sink has no end of its own, so the token stream's
        // doFinally is what lets the merge complete (normally, on error, or
        // on client cancel). Errors are NOT converted to STREAM_ERROR_MARKER
        // chunks here - they propagate as real onError signals, and the mapper
        // turns them into RUN_ERROR (it also understands the marker, for
        // consumers of /chat's output, but this path never produces one).
        Flux<String> merged =
                Flux.merge(tokens.doFinally(signal -> stepEvents.complete()), stepEvents.asFlux());

        // Durable-buffered, sequence-numbered events: every mapped event is
        // appended to the run's Redis buffer (and this pod's live tail) with
        // its seq stamped as the SSE `id:` - that is what lets
        // GET .../runs/{runId}/events rejoin this stream by seq afterwards.
        // finish() marks the run terminated however it ends.
        // The buffer is keyed on the run as scoped to this agent and caller (see
        // AgUiRunEventBuffer#scopedRunId), not on the bare client-chosen runId.
        String tenantKey = AgUiRunEventBuffer.currentTenantKey();
        String bufferId = AgUiRunEventBuffer.scopedRunId(agentId, subjectOf(principal), runId);
        runBuffer.open(tenantKey, bufferId);
        Flux<ServerSentEvent<String>> events =
                mapper.map(merged, threadId, runId, agentId)
                        .map(
                                event -> {
                                    String json = event.toJson();
                                    return toSse(runBuffer.record(tenantKey, bufferId, json), json);
                                })
                        .doFinally(signal -> runBuffer.finish(tenantKey, bufferId));
        return withHeartbeat(events);
    }

    /**
     * Rejoin a run's event stream - {@code GET /api/agents/{agentId}/agui/runs/{runId}/events}. The
     * answer to "the SSE connection dropped and the turn's events are gone": every event {@link
     * #run} emits is durable-buffered with a sequence number (see {@link AgUiRunEventBuffer}), so a
     * client reconnects with the last seq it saw and resumes from exactly there.
     *
     * <p>Resume position comes from either the standard SSE {@code Last-Event-ID} header (what a
     * browser's {@code EventSource} sends automatically on its own reconnect) or the explicit
     * {@code ?after=} parameter; the header is the fallback when both are absent/present. The
     * response is itself SSE with {@code id:} on every frame, so a subsequent drop rejoins the same
     * way.
     *
     * <p>Same-pod runs get buffered history followed by the live tail, zero gap (the sink replays
     * its whole history and this filters seq &gt; last-replayed). Runs executing on ANOTHER pod -
     * or whose live window already passed - get the buffered history and a clean stream end; the
     * client polls this endpoint again for more, until the buffer itself contains the run's
     * terminal event ({@code RUN_FINISHED}/{@code RUN_ERROR}). RUNNER-gated, same bar as {@link
     * #run}.
     */
    @GetMapping(value = "/runs/{runId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> events(
            @PathVariable UUID agentId,
            @PathVariable String runId,
            @RequestParam(name = "after", required = false) Long after,
            @RequestHeader(name = "Last-Event-ID", required = false) String lastEventId,
            Principal principal) {
        agentService.requireAccess(agentId, PermissionLevel.RUNNER);
        if (!AgUiRunEventBuffer.RUN_ID_PATTERN.matcher(runId).matches()) {
            throw new IllegalArgumentException("invalid runId");
        }
        String tenantKey = AgUiRunEventBuffer.currentTenantKey();
        // Only the caller that started the run, on this agent, resolves to its buffer; anyone else
        // gets the same empty answer as for a run that does not exist.
        String bufferId = AgUiRunEventBuffer.scopedRunId(agentId, subjectOf(principal), runId);
        long resumeAfter = after != null ? after : parseLastEventId(lastEventId);

        List<BufferedEvent> buffered = runBuffer.readAfter(tenantKey, bufferId, resumeAfter);
        long lastSeq = buffered.isEmpty() ? resumeAfter : buffered.get(buffered.size() - 1).seq();
        Flux<ServerSentEvent<String>> replay =
                Flux.fromIterable(buffered).map(e -> toSse(e.seq(), e.json()));

        if (runBuffer.isFinished(tenantKey, bufferId)) {
            return replay;
        }
        Sinks.Many<BufferedEvent> tail = runBuffer.liveTail(tenantKey, bufferId);
        if (tail == null) {
            // Not executing on this pod - the buffer above is the honest
            // answer; the client re-polls with its new last seq.
            return replay;
        }
        Flux<ServerSentEvent<String>> live =
                tail.asFlux().filter(e -> e.seq() > lastSeq).map(e -> toSse(e.seq(), e.json()));
        return withHeartbeat(Flux.concat(replay, live));
    }

    /**
     * Build the wire frame: seq as SSE {@code id:} (what Last-Event-ID echoes back), event JSON as
     * {@code data:}.
     */
    private static ServerSentEvent<String> toSse(long seq, String json) {
        var builder = ServerSentEvent.<String>builder().data(json);
        if (seq > 0) {
            builder.id(String.valueOf(seq));
        }
        return builder.build();
    }

    private static String subjectOf(Principal principal) {
        return principal != null && StringUtils.hasText(principal.getName())
                ? principal.getName()
                : "";
    }

    private static long parseLastEventId(String lastEventId) {
        if (!StringUtils.hasText(lastEventId)) {
            return 0L;
        }
        try {
            return Long.parseLong(lastEventId.trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /**
     * The agent's server-side tools as AG-UI {@code Tool} objects, for a client that wants to
     * render "what can this agent do" - the protocol's own shape ({@code name}, {@code
     * description}, {@code parameters}). VIEWER-gated, same bar as {@code
     * AgentController#listAvailableTools}, whose descriptor list this reuses. {@code parameters} is
     * the permissive empty-object schema: {@link ToolDescriptor} carries no input schema (it exists
     * for a name picker), and a fabricated one would be worse than an honest "any object".
     */
    @GetMapping(value = "/tools", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<AgUiTool> tools(@PathVariable UUID agentId) {
        agentService.requireAccess(agentId, PermissionLevel.VIEWER);
        return agentToolProvider.listAvailableToolsFor(agentId).stream()
                .map(
                        descriptor ->
                                new AgUiTool(
                                        descriptor.name(),
                                        descriptor.description(),
                                        AgUiTool.ANY_OBJECT))
                .toList();
    }

    /** AG-UI's {@code Tool} shape for {@link #tools}. */
    public record AgUiTool(String name, String description, Map<String, Object> parameters) {
        static final Map<String, Object> ANY_OBJECT =
                Map.of("type", "object", "properties", Map.of(), "additionalProperties", true);
    }

    /**
     * Interleaves a {@code :keepalive} SSE comment into {@code events} on every {@link #HEARTBEAT}
     * tick, stopping when {@code events} terminates. The ticker is fenced by a {@link Sinks.Empty}
     * completed from the main stream's {@code doFinally} rather than by sharing the main stream, so
     * the events Flux is still subscribed exactly once. {@code Sinks.empty()} replays its terminal
     * signal to a late subscriber, so the ticker also ends correctly if the main stream finishes
     * before the ticker's first subscription is wired up.
     */
    private static Flux<ServerSentEvent<String>> withHeartbeat(
            Flux<ServerSentEvent<String>> events) {
        Sinks.Empty<Void> done = Sinks.empty();
        Flux<ServerSentEvent<String>> main = events.doFinally(signal -> done.tryEmitEmpty());
        Flux<ServerSentEvent<String>> heartbeat =
                Flux.interval(HEARTBEAT, HEARTBEAT)
                        .map(tick -> ServerSentEvent.<String>builder().comment("keepalive").build())
                        .takeUntilOther(done.asMono());
        return Flux.merge(main, heartbeat);
    }
}
