package com.agenticai.core.agents.turn;

import com.agenticai.core.agents.core.AgentController;
import com.agenticai.core.agents.dto.ChatRequest;
import com.agenticai.core.agents.resumable.ResumableChatStreamService;
import com.agenticai.core.agents.tools.AgentStepEventEmitter;
import com.agenticai.core.agents.turnjournal.TurnJournalGateway;
import com.agenticai.core.chat.UpstreamProviderException;
import com.agenticai.core.guardrails.GuardrailViolationException;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.concurrent.TenantAwareSchedulers;
import java.security.Principal;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;

/**
 * Builds the SSE response of {@code POST /api/agents/{agentId}/chat} once {@link
 * AgentController#chat} has checked RUNNER access: session id and memory subject, the token stream
 * with its in-band mid-stream error marker, the opt-in step-event merge and the opt-in resumable
 * detach. Not a Spring bean - the controller builds it. A turn without step events builds and
 * returns the chat Flux on the request thread exactly as before; a step-event turn defers the
 * pre-token pipeline onto a tenant-aware worker so its progress events can be flushed while the
 * turn is still being prepared (see the comment at the call site).
 */
public final class AgentChatStreamResponder {

    private static final Logger log = LoggerFactory.getLogger(AgentController.class);

    private final AgentChatService agentChatService;
    private final Duration stepHeartbeatInterval;

    /**
     * Whether a turn the client did not tag still gets a turn reference
     * (rl.selection.server-turn-ref); null = no.
     */
    private volatile RlTurnRefPolicy rlTurnRefPolicy;

    public AgentChatStreamResponder(
            AgentChatService agentChatService, Duration stepHeartbeatInterval) {
        this.agentChatService = agentChatService;
        this.stepHeartbeatInterval = stepHeartbeatInterval;
    }

    void useRlTurnRefPolicy(RlTurnRefPolicy policy) {
        this.rlTurnRefPolicy = policy;
    }

    /**
     * @param resumable the controller's optional resumable-stream service; null or flag-off takes
     *     the unchanged stream path.
     */
    public ResponseEntity<Flux<String>> respond(
            UUID agentId,
            ChatRequest request,
            Principal principal,
            String resumableOptIn,
            ResumableChatStreamService resumable) {
        // "eval:" and "sim:" mark quality-tooling replays, which the brief trust ladder does not
        // count as guardrail
        // strikes. Those runs never come through this endpoint, so a caller-chosen id with that
        // prefix is only ever an
        // attempt to have its own blocked turns ignored.
        if (StringUtils.hasText(request.sessionId())
                && (request.sessionId().startsWith("eval:")
                        || request.sessionId().startsWith("sim:"))) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST,
                    "sessionId may not start with 'eval:' or 'sim:'.");
        }
        // The sessionId is the caller's own label and lands in log lines all the way down the
        // turn path - a legit client never needs control characters in it, and rejecting them
        // here keeps forged newlines/ANSI out of every downstream log statement.
        if (StringUtils.hasText(request.sessionId())
                && !request.sessionId()
                        .equals(request.sessionId().replaceAll("[\\p{Cntrl}]", ""))) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST,
                    "sessionId may not contain control characters.");
        }
        String sessionId =
                StringUtils.hasText(request.sessionId())
                        ? request.sessionId()
                        : UUID.randomUUID().toString();

        // Long-term memory subject key: the authenticated Keycloak principal
        // when the "security" profile is on (stable across every future
        // session this same user starts), falling back to sessionId when
        // security is off (NoSecurityConfig - no principal exists) - memory
        // is then session-scoped only, same as before this feature existed,
        // never worse.
        String subjectKey =
                principal != null && StringUtils.hasText(principal.getName())
                        ? principal.getName()
                        : sessionId;

        StringBuilder emitted = new StringBuilder();
        // "Has anything reached the wire?", which is NOT the same question as "is `emitted`
        // non-empty" (2026-09-17 audit). Every chunk delivered downstream is written and
        // flushed as its own `data:` event, which commits the HTTP status line - including an
        // EMPTY chunk, which leaves `emitted` length 0. Two real sources of those: a provider
        // that emits an empty delta on the FAST path, and the buffered depths' keepalive (see
        // AgentChatService#withBufferedKeepAlive). With the old check those turns took the
        // Flux.error branch below AFTER the response was already committed, so
        // GlobalExceptionHandler could not map the status either - the connection simply died
        // silently, which is the exact failure AgentController.STREAM_ERROR_MARKER exists to
        // prevent.
        AtomicBoolean wroteToWire = new AtomicBoolean(false);
        // Opt-in step-event streaming (2026-08-17). A live emitter ONLY when
        // this request explicitly asked; every other turn gets the shared
        // no-op, which makes AgentToolProvider skip its wrapper entirely and
        // leaves the merge operator below unbuilt - see AgentStepEventEmitter's
        // javadoc for why an activity trace could not simply be added to the
        // stream format for everyone (the LiveKit voice bridge would speak the
        // events aloud on a live call; every omnichannel adapter would paste
        // them into a customer's message).
        AgentStepEventEmitter stepEvents =
                request.stepEvents()
                        ? AgentStepEventEmitter.active(stepHeartbeatInterval)
                        : AgentStepEventEmitter.disabled();
        // request.forceFastDepth() (2026-08-06, reliability blueprint
        // follow-up) - threaded straight into the eleven-arg overload so the
        // LiveKit voice bridge (llm_core_bridge.py), the only caller of THIS
        // HTTP endpoint expected to ever set it, gets its turn dispatched as
        // FAST regardless of the agent's configured reasoning depth. Every
        // other caller of this same endpoint omits the field, Jackson
        // defaults the record's primitive boolean to false, and this call
        // is byte-identical to before.
        ChatTurnRequest turn =
                new ChatTurnRequest(
                        agentId,
                        sessionId,
                        subjectKey,
                        request.message(),
                        request.attachmentKey(),
                        request.attachmentMimeType(),
                        request.attachments(),
                        List.of(),
                        rlTurnRefPolicy == null
                                ? request.turnRef()
                                : rlTurnRefPolicy.turnRefFor(request.turnRef()),
                        request.visionSessionId(),
                        request.forceFastDepth(),
                        stepEvents,
                        false,
                        null,
                        null,
                        // The run-scoped tool selection (the playground's "this run only"
                        // switches); null = none.
                        request.toolNames());
        // ADR-0022 stage 4: this HTTP entry builds the turn context from the tenant
        // TenantContextFilter put on this request thread.
        TurnContext context = TurnContext.forEntry(TenantContext.current().orElse(null), turn);
        // Routines-from-habits loop (app.learning.routines.enabled, off by default). Observed HERE,
        // at
        // the console's own chat entry, and nowhere deeper: the turn dispatcher also serves job
        // turns,
        // scheduled runs, delegation, A2A, OpenAI-compatible, AG-UI and channel traffic, none of
        // which is "a request the person typed". Returns at once while the loop is off.
        com.agenticai.core.agents.skills.RoutineObservationGateway.observe(
                agentId,
                sessionId,
                request.message(),
                com.agenticai.core.agents.skills.RoutineObservationGateway.CHAT,
                TenantContext.current());
        // Step-event turns (the console) run the pre-token pipeline OFF the request thread.
        // AgentChatService#chat is synchronous up to the model call: preflight (moderation,
        // guardrails, hooks, cache lookups), client selection and prompt assembly (history,
        // tool resolution with MCP connections, memory recall) all complete before the Flux
        // exists. Spring MVC's ResponseBodyEmitter only flushes once this handler has returned,
        // so on the request thread nothing - not even the `phase` events - reached the browser
        // until that whole stretch was over (on a laptop with a local model: the silent minute
        // users read as "nothing is working"). Deferring onto a tenant-aware scheduler lets the
        // handler return at once; TenantAwareScheduler re-applies this thread's tenant,
        // agent-version pin and MDC on the worker (ContextSnapshot), and TurnContext#onEntryThread
        // then sees the same tenant instance. Errors before the first write still reach
        // GlobalExceptionHandler unchanged (Flux.error on an uncommitted response). Turns
        // without step events keep the synchronous build, byte for byte.
        Flux<String> reply =
                stepEvents.isEnabled()
                        ? Flux.defer(() -> agentChatService.chat(context, turn))
                                .subscribeOn(TenantAwareSchedulers.boundedElastic())
                        : agentChatService.chat(context, turn);
        Flux<String> tokens =
                reply.doOnNext(
                                chunk -> {
                                    wroteToWire.set(true);
                                    emitted.append(chunk);
                                })
                        // See AgentController.STREAM_ERROR_MARKER's javadoc. Only converts to a
                        // visible
                        // synthetic chunk once something has already streamed - an error
                        // before the first token still propagates normally so
                        // GlobalExceptionHandler gets its usual pre-commit shot at
                        // mapping it to a proper HTTP status (402/503/etc), unchanged
                        // from before this fix.
                        .onErrorResume(
                                e -> {
                                    // stepEvents.hasEmitted() (2026-08-17) widens "has anything
                                    // already streamed?" to include the step-event side channel.
                                    // It is false on every turn that didn't ask for step events, so
                                    // this condition is exactly the original one for every existing
                                    // caller. It matters on a step-event turn because tool calls
                                    // precede the model's final tokens: the first thing on the wire
                                    // is routinely a tool_call event, which commits the HTTP status
                                    // line, and a failure after that point can no longer be mapped
                                    // to a 402/503 by GlobalExceptionHandler - it has to ride the
                                    // in-band marker or the connection just dies silently. See
                                    // AgentStepEventEmitter#hasEmitted's javadoc.
                                    if (!wroteToWire.get() && !stepEvents.hasEmitted()) {
                                        return Flux.error(e);
                                    }
                                    log.warn(
                                            "Chat stream for agent {} session {} failed"
                                                    + " mid-response after {} chars already streamed",
                                            agentId,
                                            sessionId,
                                            emitted.length(),
                                            e);
                                    // On a reactor thread: read the wording in the turn's tenant.
                                    String generic =
                                            context.scope()
                                                    .get(
                                                            () ->
                                                                    genericFailureMessage(
                                                                            agentChatService
                                                                                    .promptDirectiveSettings,
                                                                            agentId));
                                    return Flux.just(
                                            AgentController.STREAM_ERROR_MARKER
                                                    + midStreamFailureMessage(e, generic));
                                });

        // THE byte-identity guarantee, in one expression: a turn that did not
        // ask for step events is handed `tokens` itself - the exact same Flux
        // this method built before this feature existed, with no operator added
        // on top - so there is nothing for the voice bridge or an omnichannel
        // adapter to see differently. The merge is also deliberately the LAST
        // thing applied, after doOnNext(emitted::append): step events must not
        // count toward `emitted` (which answers "has anything already streamed?"
        // for the mid-stream-error gate above), and they must never reach the
        // accumulators inside AgentChatService that persist a turn to session
        // memory - see the twelve-arg chat overload's javadoc.
        //
        // doFinally is what terminates the merge: the emitter's sink has no end
        // of its own, so without completing it when the token stream finishes
        // (normally, in error, or on client cancel) the merged stream would hang
        // open after the last token.
        Flux<String> stream =
                stepEvents.isEnabled()
                        ? Flux.merge(
                                tokens.doFinally(signal -> stepEvents.complete()),
                                stepEvents.asFlux())
                        : tokens;

        // Resumable streams (flag + per-request opt-in, see ResumableChatStreamService): generation
        // is detached
        // from this HTTP subscriber and every event carries an SSE id, so a dropped client can GET
        // the rest.
        // Anything else - flag off, no opt-in header, a null service in a slim test - falls through
        // to the
        // unchanged return below.
        if (resumable != null && resumable.accepts(resumableOptIn)) {
            ResumableChatStreamService.Started started =
                    resumable.start(agentId, sessionId, stream);
            return ResponseEntity.ok()
                    .header(AgentController.SESSION_ID_HEADER, sessionId)
                    .headers(journalTurnHeader(context))
                    .header(ResumableChatStreamService.TURN_ID_HEADER, started.turnId())
                    .header(HttpHeaders.CACHE_CONTROL, "no-cache")
                    .header("X-Accel-Buffering", "no")
                    .body(started.asBodyFlux());
        }

        return ResponseEntity.ok()
                .header(AgentController.SESSION_ID_HEADER, sessionId)
                .headers(journalTurnHeader(context))
                // Standard streaming-response hygiene (2026-09-25 latency audit):
                // no-cache tells intermediaries not to store or coalesce the body,
                // and X-Accel-Buffering:no is the de-facto "do not buffer this
                // upstream response" hint nginx-class proxies honor - harmless on
                // hops that ignore it, and on ones that would buffer it is the
                // difference between a token stream and a 300s hang.
                .header(HttpHeaders.CACHE_CONTROL, "no-cache")
                .header("X-Accel-Buffering", "no")
                .body(stream);
    }

    /**
     * Names the turn's journal record in the response ({@link TurnJournalGateway#TURN_ID_HEADER})
     * so the client can open the turn it just ran, or find it by the session id it already has.
     * Only while the journal is on: an id with nothing behind it would send the client to a 404.
     */
    private static java.util.function.Consumer<HttpHeaders> journalTurnHeader(TurnContext context) {
        return headers -> {
            if (TurnJournalGateway.enabled()) {
                headers.set(TurnJournalGateway.TURN_ID_HEADER, context.turnId().toString());
            }
        };
    }

    /**
     * The human half of a {@link AgentController#STREAM_ERROR_MARKER} chunk. Prefers {@link
     * UpstreamProviderException#getConsumerMessage()} for the same reason {@code
     * GlobalExceptionHandler#handleUpstreamProvider} does on the pre-commit path: a mid-stream
     * failure is not a different KIND of failure from one that happened a token earlier, so it
     * should not degrade to a vaguer message just because of when it landed. A generic sentence is
     * the fallback for everything else, deliberately - {@code getMessage()} on an arbitrary
     * exception is raw internal detail (class names, upstream JSON, stack context) and this string
     * goes straight to an end user's chat window.
     */
    public static String midStreamFailureMessage(Throwable error) {
        return midStreamFailureMessage(error, null);
    }

    /**
     * The sentence a failed reply carries when the provider gave no message of its own: the
     * agent's, its tenant's or the platform's wording of {@code message.generic_failure}, else the
     * shipped one. Read in the calling thread's tenant. Never null, never throws.
     */
    static String genericFailureMessage(PromptDirectiveSettings settings, UUID agentId) {
        try {
            String text =
                    settings.forTurn(java.time.LocalDate.now(java.time.ZoneOffset.UTC), agentId)
                            .textOrShipped(GENERIC_FAILURE_KEY);
            return text != null ? text : defaultGenericFailureMessage();
        } catch (RuntimeException e) {
            return defaultGenericFailureMessage();
        }
    }

    /** The shipped wording of the generic sentence ({@code message.generic_failure}). */
    public static String defaultGenericFailureMessage() {
        return com.agenticai.core.prompts.PromptResolver.defaultsOnly()
                .catalog()
                .defaultText(GENERIC_FAILURE_KEY);
    }

    /** Registry key of the generic failure sentence. */
    public static final String GENERIC_FAILURE_KEY = "message.generic_failure";

    /**
     * As {@link #midStreamFailureMessage(Throwable)}, with {@code genericFailure} as the generic
     * sentence (the agent's, tenant's or platform's wording of {@code message.generic_failure});
     * {@code null} = the shipped one.
     */
    public static String midStreamFailureMessage(Throwable error, String genericFailure) {
        // Bounded rather than "walk until null": a cause chain can be cyclic
        // (a wrapper that returns itself, or A->B->A), and this runs on the
        // error path of a live stream - the one place an infinite loop would
        // be hardest to notice.
        Throwable current = error;
        for (int depth = 0; current != null && depth < 16; depth++) {
            if (current instanceof UpstreamProviderException upstream
                    && StringUtils.hasText(upstream.getConsumerMessage())) {
                return upstream.getConsumerMessage();
            }
            // A guardrail block is a deliberate, user-facing safety decision
            // (not an internal failure) - its own message names the tool and
            // reason, which is exactly what the person needs to see here too.
            if (current instanceof GuardrailViolationException guardrail) {
                return guardrail.getMessage();
            }
            if (current
                    instanceof
                    com.agenticai.core.agents.spendcap.SpendCapReachedException spendCap) {
                return spendCap.getMessage();
            }
            Throwable cause = current.getCause();
            current = cause == current ? null : cause;
        }
        return genericFailure != null ? genericFailure : defaultGenericFailureMessage();
    }
}
