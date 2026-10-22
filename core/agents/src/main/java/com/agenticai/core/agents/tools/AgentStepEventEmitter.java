package com.agenticai.core.agents.tools;

import com.agenticai.core.security.concurrent.TenantAwareSchedulers;
import com.agenticai.core.security.logging.LogSafe;
import tools.jackson.core.JacksonException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Scheduler;

/**
 * Per-turn side channel that carries an agent's TOOL activity (a step trace) alongside - never
 * inside - the token stream {@code AgentChatService#chat} returns. One instance per chat turn;
 * created by {@code AgentController#chat} only when the caller explicitly asked for it, and handed
 * down through {@code AgentChatService#chat} into {@code AgentToolProvider#resolveToolsFor} so
 * {@link StepEventToolCallback} can push an event per tool invocation.
 *
 * <h2>Why this is opt-in, and why it could NOT be a format change</h2>
 *
 * The SSE chunks {@code AgentChatService#chat} produces are not a browser-only contract. Seventeen
 * consumers read that exact wire format today and all but a handful of them have no way to opt out
 * of anything new that appears on it, because they never build a {@code ChatRequest} at all:
 *
 * <ul>
 *   <li><b>The LiveKit voice bridge</b> ({@code voice-agent/app/llm_core_bridge.py}) is the worst
 *       case and the reason this design exists in this shape. Its {@code _handle_sse_line} strips
 *       the {@code data:} prefix, checks for the ONE marker it mirrors ({@code
 *       AgentController#STREAM_ERROR_MARKER}), and hands everything else straight to a LiveKit
 *       {@code ChatChunk} - which goes to {@code tts_node}. An unrecognized framed chunk on that
 *       stream is not a rendering glitch: the TTS engine <i>speaks the JSON out loud</i> to a human
 *       on a live call, and the chunk additionally trips the bridge's {@code _first_token_sent}
 *       stage signal before any real token has arrived.
 *   <li><b>Every omnichannel adapter</b> (WhatsApp, Telegram, Slack, Discord, X, email, Twilio,
 *       plus {@code WorkflowNodeExecutor}, {@code QuickTaskService}, {@code
 *       McpCallAgentToolCallback}) collapses the whole {@code Flux} with {@code
 *       collectList()}/{@code collect(joining())} and sends the result to a customer, or feeds it
 *       to the next workflow node as that node's output value. A step event there becomes part of
 *       the customer's message body.
 *   <li>The two IDE clients ({@code clients/shared/src/api/sse.ts}, {@code
 *       clients/jetbrains/.../ApiClient.kt}) append any unrecognized payload verbatim into their
 *       chat buffer.
 * </ul>
 *
 * So "emit the events always and let clients filter them" is not available: it would silently break
 * twelve consumers that cannot be changed from this request's perspective and, in the voice case,
 * would be audible to end users. The flag is therefore a property of the REQUEST ({@code
 * ChatRequest#stepEvents}), which only the HTTP surface can set, and when it is false this class's
 * {@link #DISABLED} instance is used and {@code AgentToolProvider}'s wrap chain is left
 * object-identical - no wrapper, no sink, no merge operator, no chunk.
 *
 * <h2>Framing</h2>
 *
 * {@link #STEP_EVENT_MARKER} plus one single-line JSON object per chunk; the rules (in-band prefix,
 * not an SSE event name; never more than one line) and why they hold are documented on {@link
 * AgentStepEventPayloads}, which builds and serializes every payload.
 *
 * <h2>Fail-open, like every other optional enhancement in this codebase</h2>
 *
 * Same posture as {@code TaskPlanningService}/{@code ReflexionService}/{@code
 * AnswerConsistencyChecker}: a step event that cannot be serialized, or a sink that has already
 * terminated because the client hung up, is swallowed at DEBUG. An activity trace is a nicety; it
 * must never be able to fail a turn that would otherwise have answered. This mirrors {@code
 * AgentJobObservabilityController}'s own {@code emit()} helper, which swallows a per-entry
 * serialization failure for the same reason.
 */
public final class AgentStepEventEmitter {

    private static final Logger log = LoggerFactory.getLogger(AgentStepEventEmitter.class);

    /**
     * Prefix on every step-event chunk. See the class javadoc for why this is an in-band prefix
     * rather than an SSE event name, and why the suffix is random-looking. Mirrored in {@code
     * web/app/lib/api.ts} - keep the two byte-identical.
     */
    public static final String STEP_EVENT_MARKER = "@@AGENT_STEP_EVENT_4d81ba@@";

    /**
     * The one shared no-op instance. Handed to every code path that did not explicitly ask for step
     * events, so callers never need a null check and, more importantly, so {@code
     * AgentToolProvider#guardAndLog} has a cheap, allocation-free {@code isEnabled()} test to skip
     * wrapping entirely.
     */
    private static final AgentStepEventEmitter DISABLED = new AgentStepEventEmitter(false);

    /**
     * The emitter whose {@link #toolCall} is currently executing on THIS thread, if any - see
     * {@link #current()} for the one consumer and why a thread binding is the only workable carrier
     * for it.
     */
    private static final ThreadLocal<AgentStepEventEmitter> CURRENT_TOOL_SCOPE =
            new ThreadLocal<>();

    private final boolean enabled;
    private final Sinks.Many<String> sink;

    /**
     * Correlates a {@code tool_call} with the {@code tool_result}/{@code error} that closes it,
     * within one turn.
     */
    private final AtomicInteger stepIds = new AtomicInteger(0);

    /**
     * See {@link #hasEmitted()} - written under {@code #emit}'s monitor, read from the request
     * thread.
     */
    private volatile boolean emittedAnything;

    /**
     * The agent this turn belongs to, recorded by {@link #runStarted} so a later {@link #handoff}
     * can name the delegating side without the caller (deep inside a tool call) needing to know it
     * - see {@link #runAgentId()}.
     */
    private volatile UUID runAgentId;

    /**
     * Liveness-beat cadence for this turn, or {@code null}/{@code ZERO} for no heartbeat - see
     * {@link #heartbeat()}'s javadoc for what the beats are for. Set once at construction; the
     * scheduler is a field only so tests can drive {@code Flux.interval} on virtual time.
     */
    private final Duration heartbeatInterval;

    private final Scheduler heartbeatScheduler;

    /**
     * The running {@code Flux.interval} subscription, started by {@link #runStarted} and stopped by
     * {@link #complete()}.
     */
    private volatile Disposable heartbeatTask;

    /**
     * When {@link #runStarted} fired - the heartbeat payload's {@code ms} is elapsed time since
     * this.
     */
    private volatile Instant runStartedAt;

    /**
     * When this emitter was made, i.e. when the turn was accepted; {@link #phase} reports time
     * since.
     */
    private final Instant createdAt = Instant.now();

    /**
     * True once {@code run_started} is on the sink - see {@link #memoryRecall}. Guarded by this
     * object's monitor.
     */
    private boolean runStartedEmitted;

    /**
     * A {@code memory} event produced before {@code run_started}, held until it can follow it.
     * Guarded by this object's monitor.
     */
    private Map<String, Object> deferredMemoryEvent;

    private AgentStepEventEmitter(boolean enabled) {
        this(enabled, Duration.ZERO, TenantAwareSchedulers.parallel());
    }

    private AgentStepEventEmitter(
            boolean enabled, Duration heartbeatInterval, Scheduler heartbeatScheduler) {
        this.enabled = enabled;
        this.heartbeatInterval = heartbeatInterval;
        this.heartbeatScheduler = heartbeatScheduler;
        // Unicast + onBackpressureBuffer, the same shape
        // AgentJobObservabilityController uses for its own SSE side channel:
        // events produced before the merge operator subscribes are buffered
        // and replayed to that first subscriber, and there is only ever one
        // subscriber (the Flux.merge in AgentController#chat) - see
        // McpClientTimeoutConfig's note on the single-Subscriber caveat.
        this.sink = enabled ? Sinks.many().unicast().onBackpressureBuffer() : null;
    }

    /**
     * The shared no-op instance - every event method returns immediately and {@link #asFlux()} is
     * empty.
     */
    public static AgentStepEventEmitter disabled() {
        return DISABLED;
    }

    /**
     * A live emitter for one turn. Only {@code AgentController#chat} creates these, and only when
     * the request asked.
     */
    public static AgentStepEventEmitter active() {
        return new AgentStepEventEmitter(true);
    }

    /**
     * A live emitter that additionally emits {@code heartbeat} step events every {@code
     * heartbeatInterval} for the life of the turn - see {@link #heartbeat()}. {@code
     * null}/zero/negative disables the beats, leaving a plain {@link #active()} emitter.
     */
    public static AgentStepEventEmitter active(Duration heartbeatInterval) {
        return new AgentStepEventEmitter(true, heartbeatInterval, TenantAwareSchedulers.parallel());
    }

    /**
     * Test seam: same as {@link #active(Duration)} but on an injectable scheduler so virtual time
     * can drive it.
     */
    static AgentStepEventEmitter active(Duration heartbeatInterval, Scheduler heartbeatScheduler) {
        return new AgentStepEventEmitter(true, heartbeatInterval, heartbeatScheduler);
    }

    /**
     * False for {@link #disabled()}. Checked by {@code AgentToolProvider#guardAndLog} before it
     * adds {@link StepEventToolCallback} to the wrap chain, and by {@code AgentController#chat}
     * before it adds the merge operator - both so that a turn without step events runs through
     * byte-identical object graphs, not merely equivalent ones.
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * True once at least one step event has actually reached the sink.
     *
     * <p>Exists for one specific reason, and it is not observability: {@code AgentController#chat}
     * decides whether a failure can still be mapped to a real HTTP status by asking "has anything
     * already streamed?" - because once a chunk goes out the status line is committed and the only
     * remaining way to tell the user anything is the in-band {@code STREAM_ERROR_MARKER} chunk.
     * Before step events existed, "anything" and "any token" were the same question, so a {@code
     * StringBuilder} of tokens answered it. They are no longer the same: tool calls happen BEFORE
     * the model's final tokens, so on a step-event turn the very first thing on the wire is
     * routinely a {@code tool_call} event. Without this flag, a provider failure after that event
     * but before the first token would look "pre-commit" to the controller, propagate as a raw
     * error onto an already-committed response, and the connection would just die silently -
     * exactly the {@code net::ERR_QUIC_PROTOCOL_ERROR} failure {@code STREAM_ERROR_MARKER} was
     * written to eliminate. This flag keeps that gate honest.
     */
    public boolean hasEmitted() {
        return emittedAnything;
    }

    /**
     * The side channel itself, merged with the token stream at the controller boundary and nowhere
     * else.
     */
    public Flux<String> asFlux() {
        return enabled ? sink.asFlux() : Flux.empty();
    }

    /**
     * Terminates the side channel. Called from the token stream's {@code doFinally} in {@code
     * AgentController#chat} so {@code Flux.merge} completes: the sink has no natural end of its
     * own, and without this the merged stream would hang open after the last token.
     */
    public void complete() {
        if (!enabled) {
            return;
        }
        // Stop the beats first: a heartbeat racing in after the sink completes
        // is just a dropped event (emit() swallows it), but disposing here
        // keeps the interval from ticking forever on a dead turn.
        Disposable task = heartbeatTask;
        heartbeatTask = null;
        if (task != null) {
            task.dispose();
        }
        sink.tryEmitComplete();
    }

    /**
     * A pre-token pipeline stage began ({@code guardrails}, {@code routing}, {@code context},
     * {@code model}). Unlike {@link #memoryRecall} these are NOT held until {@code run_started}:
     * their whole point is to reach the client while the turn is still being prepared, which is
     * before the reply Flux is subscribed. Carries the time since the turn was accepted.
     */
    public void phase(String phase) {
        if (!enabled) {
            return;
        }
        emit(
                AgentStepEventPayloads.phase(
                        phase, Duration.between(createdAt, Instant.now()).toMillis()));
    }

    /**
     * A tool is about to run. Returns the step id to pass back into {@link #toolResult}/{@link
     * #toolError} so a client can pair them up even when two tools run concurrently.
     *
     * @param arguments the LLM-constructed argument JSON, redacted and capped by {@link
     *     AgentStepEventRedactor} before it leaves this process
     */
    public int toolCall(String toolName, String arguments) {
        if (!enabled) {
            return 0;
        }
        // Tool-scope binding for handoff() - see current(). Bound here, on the
        // thread that is about to run the tool, and unbound by whichever of
        // toolResult/toolError closes this step (StepEventToolCallback always
        // calls exactly one of them, on the same thread, including on the
        // exception path).
        CURRENT_TOOL_SCOPE.set(this);
        int id = stepIds.incrementAndGet();
        emit(AgentStepEventPayloads.toolCall(id, toolName, arguments));
        return id;
    }

    /**
     * A tool returned. {@code output} has already been through the wrap chain's own sanitization by
     * the time it reaches here - see {@link StepEventToolCallback}'s javadoc for exactly which
     * passes have run and why that ordering is load-bearing - and is additionally truncated to a
     * short preview here, because the trace is a summary of what happened, not a second copy of the
     * tool's payload.
     */
    public void toolResult(int stepId, String toolName, String output, long elapsedMillis) {
        toolResult(stepId, toolName, output, elapsedMillis, null);
    }

    /**
     * As above, plus an optional additive {@code app} pointer (MCP Apps, {@code
     * app.mcp.apps.enabled}): {@code {appId, server, tool, uri}} - a handle, never the HTML. Null
     * (always, with the flag off) adds no key, so the event is byte-identical to the four-argument
     * form.
     */
    public void toolResult(
            int stepId,
            String toolName,
            String output,
            long elapsedMillis,
            Map<String, Object> app) {
        if (!enabled) {
            return;
        }
        CURRENT_TOOL_SCOPE.remove();
        emit(AgentStepEventPayloads.toolResult(stepId, toolName, output, elapsedMillis, app));
    }

    /**
     * A tool threw. Note this is genuinely rare on the outermost wrap layer: {@code
     * TenantContextPropagatingToolCallback} converts most {@code RuntimeException}s into a friendly
     * tool RESULT (see its 2026-08-13 note), and a guardrail block surfaces as a result too - so
     * this fires for the residual "nothing downstream could turn this into an answer" case, which
     * is exactly the case worth showing in a trace.
     */
    public void toolError(int stepId, String toolName, String message, long elapsedMillis) {
        if (!enabled) {
            return;
        }
        CURRENT_TOOL_SCOPE.remove();
        emit(AgentStepEventPayloads.toolError(stepId, toolName, message, elapsedMillis));
    }

    // ------------------------------------------------------------------
    // Run-level vocabulary (2026-09-09, AG-UI alignment; heartbeat added
    // 2026-09-25 - see heartbeat()'s javadoc). ADDITIONAL
    // event types on the same marker, same single-line JSON, same opt-in
    // gate - a turn that did not ask for step events still runs through the
    // shared DISABLED instance and every method below returns before touching
    // anything. Consumers that only understand tool_call/tool_result/error
    // (the web console's AgentActivityTrace as of this writing) must ignore
    // unknown `type` values; they already do, since the event union was
    // documented as open in web/app/lib/api.ts. AgUiEventMapper is the
    // consumer that maps ALL of these onto the AG-UI protocol.
    // ------------------------------------------------------------------

    /**
     * The turn is about to start streaming (tools resolved, history loaded, model call imminent).
     * Emitted by {@code AgentChatService#chat} from the token stream's {@code doOnSubscribe}, so it
     * is the first thing on the wire on every step-event turn.
     *
     * <p><b>Consequence worth knowing about:</b> this commits the HTTP status line at subscription
     * time on every step-event turn, so a provider failure that happens BEFORE the first token
     * (auth/billing/model errors reject the request immediately) now rides the in-band {@code
     * STREAM_ERROR_MARKER} path instead of mapping to a 402/503 through {@code
     * GlobalExceptionHandler}. That is deliberate and confined to the opt-in surface: on a
     * step-event turn "the very first thing on the wire is routinely a tool_call event" already
     * (see {@link #hasEmitted}), so this only makes the early-commit universal rather than
     * tool-dependent, and it is exactly what the AG-UI endpoint needs (a {@code RUN_ERROR} after
     * {@code RUN_STARTED}, never a bare HTTP error after events have flowed). Turns without step
     * events are untouched.
     */
    public void runStarted(String sessionId, UUID agentId) {
        if (!enabled) {
            return;
        }
        this.runAgentId = agentId;
        this.runStartedAt = Instant.now();
        emit(AgentStepEventPayloads.runStarted(sessionId, agentId));
        releaseDeferredMemoryEvent();
        startHeartbeat();
    }

    /**
     * Long-term memory was put into this turn's context (the user's own notes and/or recalled
     * facts). Carries labels, counts and fact ids only - never the remembered text - so a client
     * can show "used your profile note and 2 saved memories" and link to the memory page.
     *
     * <p>Recall runs in the pre-turn fan-out, usually BEFORE the token stream is subscribed.
     * Emitting straight away would put this event ahead of {@code run_started} and flip {@link
     * #hasEmitted()} before anything is on the wire, so an event that arrives early is held and
     * released right after {@code run_started}. If the run never starts, it is dropped.
     *
     * @param blockLabels labels of the user-written blocks that were rendered (e.g. {@code
     *     persona}, {@code project:acme}); may be null
     * @param factIds ids of the recalled private and shared facts; may be null
     */
    public void memoryRecall(
            int blockCount,
            List<String> blockLabels,
            int privateFacts,
            int sharedFacts,
            List<UUID> factIds) {
        if (!enabled) {
            return;
        }
        Map<String, Object> event =
                AgentStepEventPayloads.memoryRecall(
                        blockCount, blockLabels, privateFacts, sharedFacts, factIds);
        synchronized (this) {
            if (!runStartedEmitted) {
                deferredMemoryEvent = event;
                return;
            }
            emit(event);
        }
    }

    /**
     * Marks {@code run_started} as sent and releases a {@code memory} event that arrived before it.
     */
    private synchronized void releaseDeferredMemoryEvent() {
        runStartedEmitted = true;
        Map<String, Object> held = deferredMemoryEvent;
        deferredMemoryEvent = null;
        if (held != null) {
            emit(held);
        }
    }

    /**
     * A {@code heartbeat} step event every {@link #heartbeatInterval} from subscribe until the turn
     * ends. This is the liveness signal that keeps a healthy-but-silent turn from being killed by
     * idle timeouts (2026-09-25 latency audit): a FAST turn writes {@code run_started} at subscribe
     * and can then legitimately go quiet for minutes while the pre-turn fan-out runs, a tool
     * executes (a Playwright navigation is documented at 60+s), or hosted-chain failover retries a
     * dead provider. During that silence the browser's 60s stream-idle timer and Cloudflare's ~100s
     * origin-response limit both read the connection as dead - the client fails a turn the server
     * was still successfully executing, surfacing the misleading "network issue"/"too long for
     * response" errors. One small JSON beat per interval keeps every layer's idle timer re-armed.
     * The beats ride the opt-in side channel only: a non-step-event turn (voice, omnichannel, IDE)
     * never sees one - see the class javadoc.
     *
     * <p>Started from {@link #runStarted} rather than {@code active()} so the task's lifetime is
     * bound to an actual subscription: an emitter whose token stream is never subscribed (a
     * synchronous failure before the Flux is built) never starts ticking, and a tick before
     * run_started cannot reorder the trace's first event.
     */
    private void startHeartbeat() {
        if (heartbeatInterval == null
                || heartbeatInterval.isZero()
                || heartbeatInterval.isNegative()) {
            return;
        }
        heartbeatTask =
                Flux.interval(heartbeatInterval, heartbeatInterval, heartbeatScheduler)
                        .subscribe(tick -> heartbeat());
    }

    /**
     * One liveness beat. Carries only {@code type} and elapsed {@code ms} - deliberately not a
     * {@code tool_call}-shaped row, so consumers can tell "still working" apart from real activity
     * (the web console drops these from the trace entirely; they exist to keep the wire alive).
     * Emitted through the same synchronized fail-open {@link #emit} as every other event: a beat
     * racing {@link #complete()} is dropped harmlessly.
     */
    private void heartbeat() {
        emit(AgentStepEventPayloads.heartbeat(runStartedAt));
    }

    /**
     * The turn's token stream completed normally. NOT emitted on error or client cancel - a failed
     * turn's terminal signal is the existing {@code STREAM_ERROR_MARKER} chunk (chat endpoint) or
     * {@code RUN_ERROR} (AG-UI), and a run must never end with both.
     */
    public void runFinished(long elapsedMillis) {
        if (!enabled) {
            return;
        }
        emit(AgentStepEventPayloads.runFinished(elapsedMillis));
    }

    /**
     * {@code TaskPlanningService} produced a step plan for a PLANNED-depth turn (or a revised one
     * after a tool failure). The text goes through the same flatten/cap as a tool preview: a plan
     * is model output and can be long, and the trace is a summary, not a transcript.
     */
    public void plan(String text) {
        if (!enabled) {
            return;
        }
        emit(AgentStepEventPayloads.plan(text));
    }

    /**
     * The model's own reasoning text (Ollama {@code reasoning}, DeepSeek-style {@code
     * reasoning_content}) as it streams - surfaced by {@code ReasoningEmitAdvisor} per chunk so a
     * console can show live thinking. Same flatten/cap as plan: reasoning is model output and can
     * be long, and the trace is a summary, not a transcript.
     */
    public void thinking(String text) {
        if (!enabled) {
            return;
        }
        emit(AgentStepEventPayloads.thinking(text));
    }

    /**
     * A guardrail blocked something this turn. {@code tool} is the tool whose output tripped it
     * ({@code GuardrailViolationException#getToolName}), or a direction label such as {@code
     * "input"} for the inbound-message content guardrails, which have no tool. The reason is
     * redacted/capped like any other preview because block reasons can quote the offending text.
     */
    public void guardrail(String tool, String reason) {
        if (!enabled) {
            return;
        }
        emit(AgentStepEventPayloads.guardrail(tool, reason));
    }

    /**
     * An agent lifecycle hook ({@code app.agent-hooks}, see {@code
     * com.agenticai.core.agents.hooks}) made a decision this turn: a block, an approval
     * requirement, a redaction, a limit hit, a webhook verdict, an appended note, or a hook
     * failure. Opt-in like every step event, so a turn without step events never sees it. Carries
     * the point, the hook id, the action and the outcome - never tool arguments or output; the
     * reason is redacted and capped like any other preview.
     *
     * @param point the hook point name, e.g. {@code BEFORE_TOOL}
     * @param decision {@code deny}, {@code require_approval}, {@code redacted}, {@code limited},
     *     {@code note}, {@code allow}, {@code logged} or {@code error}
     * @param tool the tool concerned, or {@code null} at a run-level point
     */
    public void hook(
            String point,
            String hookId,
            String action,
            String decision,
            String tool,
            String reason) {
        if (!enabled) {
            return;
        }
        emit(AgentStepEventPayloads.hook(point, hookId, action, decision, tool, reason));
    }

    /**
     * This turn's agent delegated a sub-question to a collaborator ({@code
     * AgentChatService#delegateCall}, reached through {@code DelegateToAgentToolCallback}). Fires
     * alongside - not instead of - the {@code tool_call} the delegation tool itself produces; the
     * tool event says "a tool named ask_agent_x ran", this one says which agent ids were on either
     * side of the handoff, which is what a multi-agent trace UI needs to draw an edge.
     */
    public void handoff(UUID fromAgent, UUID toAgent) {
        if (!enabled) {
            return;
        }
        emit(AgentStepEventPayloads.handoff(fromAgent, toAgent));
    }

    /**
     * A parallel swarm fan-out ({@code DelegateToTeamToolCallback}) dispatched or joined. {@code
     * phase} is {@code "start"} (with {@code members} = the team slugs addressed) or {@code
     * "finish"} (with {@code results} = the per-branch status list). {@code runId} correlates the
     * two and matches the {@code delegated:swarm:<runId>:} session-id prefix the branches ran
     * under, so the trace event and the per-session telemetry join. Per-branch {@code handoff}
     * edges are emitted separately at dispatch - this event is the team-level bracket around them.
     * Emitted on the tool's calling thread: the emitter's tool-scope binding is a ThreadLocal that
     * never reaches the virtual threads the branches run on.
     */
    public void swarm(
            String phase, String runId, List<String> members, List<Map<String, Object>> results) {
        if (!enabled) {
            return;
        }
        emit(AgentStepEventPayloads.swarm(phase, runId, members, results));
    }

    /**
     * The model searched the deferred tool catalog ({@code search_tools}, see {@code
     * com.agenticai.core.agents.tools.search}). Emitted alongside - not instead of - the generic
     * {@code tool_call}/{@code tool_result} pair the search tool itself produces: those say "a tool
     * named search_tools ran", this one carries the human line a trace shows ({@code summary}:
     * "searched tools: <query> -> n") plus the matched tool NAMES (never their descriptions or
     * schemas). An unknown {@code type} is rendered as a generic labelled row by the console and
     * ignored by the AG-UI mapper, so no consumer needs to change.
     */
    public void toolSearch(String query, int matched, List<String> tools) {
        if (!enabled) {
            return;
        }
        emit(AgentStepEventPayloads.toolSearch(query, matched, tools));
    }

    /**
     * The prompt view was shrunk to stay inside the model's context window ({@code
     * app.context.compaction.*}): older tool results were cleared and/or the oldest turns were
     * replaced by a summary. Only the prompt the model sees changed - session history and {@code
     * tool_call_log} are untouched. Carries counts only (never any text), plus a human {@code
     * summary} line a trace shows. An unknown {@code type} renders as a generic labelled row in the
     * console, so no consumer has to change.
     *
     * @param tokensBefore estimated prompt tokens before the change
     * @param tokensAfter estimated prompt tokens after it
     * @param clearedToolResults how many tool results became placeholders
     * @param compactedMessages how many conversation messages were folded into the summary
     */
    public void contextCompacted(
            int tokensBefore, int tokensAfter, int clearedToolResults, int compactedMessages) {
        if (!enabled) {
            return;
        }
        emit(
                AgentStepEventPayloads.contextCompacted(
                        tokensBefore, tokensAfter, clearedToolResults, compactedMessages));
    }

    /**
     * The agent id recorded by {@link #runStarted}, or {@code null} before it ran (and always for
     * {@link #disabled()}).
     */
    public UUID runAgentId() {
        return runAgentId;
    }

    /**
     * The live emitter whose {@link #toolCall} is in progress on the calling thread, if any. Exists
     * for exactly one consumer, {@code AgentChatService#delegateCall}, and for one structural
     * reason: that method runs INSIDE a tool call ({@code StepEventToolCallback -> ... ->
     * DelegateToAgentToolCallback -> delegateCall}, all synchronous {@code String call(String)}
     * frames on one thread) and has no parameter through which the outer turn's emitter could reach
     * it - {@code DelegateToAgentToolCallback} is constructed by {@code
     * AgentToolProvider#resolveCollaboratorTools} without one, and widening that constructor would
     * ripple through a wrap chain that is not step-event-aware by design. The binding is scoped to
     * the tool call ({@link #toolCall} sets it, {@link #toolResult}/{@link #toolError} clear it),
     * never to a request thread, so it cannot leak across turns the way the class javadoc warns a
     * request-scoped ThreadLocal would: the tool's own thread is the only thread that ever sees it,
     * and only while that tool is running. A delegated collaborator's own tools resolve via {@code
     * resolveToolsForDelegatedCall} with no emitter, so nested bindings do not occur.
     */
    public static Optional<AgentStepEventEmitter> current() {
        AgentStepEventEmitter bound = CURRENT_TOOL_SCOPE.get();
        return bound != null && bound.enabled ? Optional.of(bound) : Optional.empty();
    }

    /**
     * {@code synchronized} because tool calls can be dispatched concurrently by Spring AI's
     * reactive tool-execution path, and {@code Sinks.Many} is explicitly NOT safe for concurrent
     * {@code emitNext} - serializing here is cheaper and far more obvious than a busy-looping
     * emit-failure handler, given the volume (a handful of events per turn, bounded by {@code
     * GuardedToolCallback}'s step limit).
     *
     * <p>Every failure path is swallowed: a {@code FAIL_TERMINATED}/{@code FAIL_CANCELLED} result
     * just means the person closed the tab while a tool was still running, and a serialization
     * failure means one event is missing from a trace. Neither is worth failing a turn over - see
     * the class javadoc's fail-open note.
     */
    private synchronized void emit(Map<String, Object> event) {
        String json;
        try {
            json = AgentStepEventPayloads.toJson(event);
        } catch (JacksonException e) {
            log.debug(
                    "Dropping a step event for tool {} - could not serialize it",
                    LogSafe.clean(event.get("tool")),
                    e);
            return;
        }
        Sinks.EmitResult result = sink.tryEmitNext(STEP_EVENT_MARKER + json);
        if (result.isFailure()) {
            log.debug(
                    "Dropping a step event for tool {} - sink said {}",
                    LogSafe.clean(event.get("tool")),
                    LogSafe.clean(result));
            return;
        }
        // Set only on a SUCCESSFUL emit: a dropped event never reached the
        // wire, so it never committed the response either, and claiming
        // otherwise would push the controller onto the in-band error path when
        // it could still have returned a proper HTTP status. See hasEmitted().
        emittedAnything = true;
    }
}
