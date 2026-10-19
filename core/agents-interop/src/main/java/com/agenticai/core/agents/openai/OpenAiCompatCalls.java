package com.agenticai.core.agents.openai;

import com.agenticai.core.agents.core.Agent;
import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.dto.AgentResponse;
import com.agenticai.core.agents.tools.AgentStepEventEmitter;
import com.agenticai.core.agents.turn.AgentChatService;
import com.agenticai.core.agents.turn.ChatTurnRequest;
import com.agenticai.core.agents.turn.TurnContext;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.sharing.PermissionLevel;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.Principal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Signal;
import reactor.core.publisher.Sinks;

/**
 * The part of the OpenAI-compatible surface that is the same for every wire format ({@code
 * /v1/chat/completions} and {@code /v1/responses}): who the caller is, the per-caller rate limit,
 * {@code model} to agent resolution, the stateless/server-held session id, running the agent turn,
 * and bridging a token stream onto an SSE response.
 *
 * <p>Not a Spring bean: each controller builds one from its own constructor arguments, so both go
 * through exactly the same auth, scope, rate-limit and budget path (spend is metered inside {@link
 * AgentChatService}, keyed by the subject returned from {@link #authenticate}). Wire-format
 * specifics (request shape, response/event JSON) stay in the controllers and their mappers.
 */
final class OpenAiCompatCalls {

    static final Duration HEARTBEAT = Duration.ofSeconds(15);

    /**
     * Who is calling: Keycloak subject or synthetic {@code mcp-external:<grantId>} subject, tenant,
     * grant scope.
     */
    record Caller(String subject, String tenant, Optional<Set<UUID>> grant) {}

    private final AgentService agentService;
    private final AgentChatService agentChatService;
    private final OpenAiCompatRateLimiter rateLimiter;
    private final int maxPromptChars;
    private final long blockingTimeoutSeconds;
    private final long firstTokenWaitSeconds;
    private final ObjectProvider<OpenAiCompatCallerScope> callerScope;

    OpenAiCompatCalls(
            AgentService agentService,
            AgentChatService agentChatService,
            OpenAiCompatRateLimiter rateLimiter,
            int maxPromptChars,
            long blockingTimeoutSeconds,
            long firstTokenWaitSeconds,
            ObjectProvider<OpenAiCompatCallerScope> callerScope) {
        this.agentService = agentService;
        this.agentChatService = agentChatService;
        this.rateLimiter = rateLimiter;
        this.maxPromptChars = maxPromptChars;
        this.blockingTimeoutSeconds = blockingTimeoutSeconds;
        this.firstTokenWaitSeconds = firstTokenWaitSeconds;
        this.callerScope = callerScope;
    }

    int maxPromptChars() {
        return maxPromptChars;
    }

    long blockingTimeoutSeconds() {
        return blockingTimeoutSeconds;
    }

    /**
     * The grant's exposed agent ids when this request was authenticated by a {@code vmcp_} key,
     * else empty.
     */
    Optional<Set<UUID>> grantScope() {
        OpenAiCompatCallerScope scope = callerScope.getIfAvailable();
        return scope == null ? Optional.empty() : scope.exposedAgentIds();
    }

    /**
     * Grant-key caller: exactly the agents its grant exposes; JWT caller: the agents the user can
     * see.
     */
    List<AgentResponse> visibleAgents() {
        Optional<Set<UUID>> scope = grantScope();
        if (scope.isEmpty()) {
            return agentService.listAll();
        }
        List<AgentResponse> granted = new ArrayList<>();
        for (UUID id : scope.get()) {
            try {
                granted.add(agentService.getGrantedAgent(id));
            } catch (RuntimeException deletedOrUnavailable) {
                // An exposed id whose agent was deleted since the grant was saved is simply not
                // listed.
            }
        }
        return granted;
    }

    /**
     * Identifies the caller and applies the per-caller rate limit.
     *
     * @throws OpenAiCompatException 401 when there is no proven identity, 429 when over the rate
     *     limit
     */
    Caller authenticate(Principal principal) {
        Optional<Set<UUID>> grant = grantScope();
        String subject;
        if (grant.isPresent()) {
            // Grant-key caller: no Keycloak principal; identity is the synthetic per-grant subject
            // the key filter set ("mcp-external:<grantId>"), so budget, rate limit, memory and the
            // tool-call audit are all attributed to that grant.
            subject =
                    TenantContext.current()
                            .map(TenantContext::getSubject)
                            .filter(StringUtils::hasText)
                            .orElse(null);
        } else {
            subject =
                    principal != null && StringUtils.hasText(principal.getName())
                            ? principal.getName()
                            : null;
        }
        if (subject == null) {
            // Fail closed: SecurityConfig lets a vmcp_ bearer through on its prefix alone and only
            // the
            // key filter proves it. No filter (mcpserver beans absent) or no principal -> 401.
            throw new OpenAiCompatException(
                    401, "invalid_request_error", "invalid_api_key", "Incorrect API key provided.");
        }
        String tenant = TenantContext.current().map(TenantContext::getTenantId).orElse("none");
        long retryAfter = rateLimiter.tryAcquire(tenant + ":" + subject);
        if (retryAfter > 0) {
            throw new OpenAiCompatException(
                    429,
                    "rate_limit_exceeded",
                    "rate_limit_exceeded",
                    "Rate limit reached for requests. Please retry after "
                            + retryAfter
                            + " seconds.",
                    retryAfter);
        }
        return new Caller(subject, tenant, grant);
    }

    /**
     * Always "oai-" + opaque hash: AgentController.chat guards callers from forging "eval:"/"sim:"
     * session prefixes (which the trust ladder treats specially); this fixed prefix makes that
     * bypass unreachable here. Do not let a client-supplied value become the id prefix (pinned by
     * OpenAiCompatControllerTest).
     */
    String sessionId(Caller caller, String sessionHeader) {
        return StringUtils.hasText(sessionHeader)
                ? scopedSessionId(caller.tenant(), caller.subject(), sessionHeader.trim())
                : "oai-" + UUID.randomUUID();
    }

    /**
     * Runs one real agent turn (the agent's own prompt, model, memory, guardrails, server-side
     * tools).
     */
    Flux<String> runAgent(UUID agentId, String sessionId, Caller caller, String prompt) {
        ChatTurnRequest chatTurn =
                new ChatTurnRequest(
                        agentId,
                        sessionId,
                        caller.subject(),
                        prompt,
                        null,
                        null,
                        null,
                        List.of(),
                        null,
                        null,
                        false,
                        AgentStepEventEmitter.disabled(),
                        false);
        TurnContext turnContext =
                TurnContext.forEntry(TenantContext.current().orElse(null), chatTurn);
        return agentChatService.chat(turnContext, chatTurn);
    }

    /**
     * Session ids are scoped to tenant + caller: two users sending the same {@code X-Session-Id}
     * (e.g. "default") must never share history.
     */
    static String scopedSessionId(String tenant, String subject, String header) {
        String capped = header.substring(0, Math.min(header.length(), 120));
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            byte[] d =
                    sha.digest(
                            (tenant + "\n" + subject + "\n" + capped)
                                    .getBytes(StandardCharsets.UTF_8));
            return "oai-" + HexFormat.of().formatHex(d, 0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Roughly four characters per token: the chat service streams text and does not surface
     * provider counts.
     */
    static long estimateTokens(long chars) {
        return (chars + 3L) / 4L;
    }

    /**
     * Agent id first, then an exact (case-insensitive) unique name. JWT callers are gated at
     * RUNNER; grant-key callers are confined to the agents their grant exposes (the grant IS the
     * authorization decision, as in {@code McpCallAgentToolCallback}; the synthetic key subject has
     * no per-user ResourceGrant, so requireAccess would always refuse it).
     */
    UUID resolveAgent(String model, Optional<Set<UUID>> grant) {
        UUID id = null;
        try {
            id = UUID.fromString(model);
        } catch (IllegalArgumentException notAUuid) {
            List<AgentResponse> byName =
                    (grant.isPresent() ? visibleAgents() : agentService.listAll())
                            .stream()
                                    .filter(
                                            a ->
                                                    a.name() != null
                                                            && a.name().equalsIgnoreCase(model))
                                    .toList();
            if (byName.size() > 1) {
                throw new OpenAiCompatException(
                        400,
                        "invalid_request_error",
                        "ambiguous_model",
                        "More than one agent is named '"
                                + model
                                + "'; use the agent id from GET /v1/models.");
            }
            if (byName.size() == 1) {
                id = byName.get(0).id();
            }
        }
        if (id == null || (grant.isPresent() && !grant.get().contains(id))) {
            throw new OpenAiCompatException(
                    404,
                    "invalid_request_error",
                    "model_not_found",
                    "The model '" + model + "' does not exist or you do not have access to it.");
        }
        if (grant.isPresent()) {
            agentService.getGrantedAgent(
                    id); // AgentNotFoundException -> 404 via OpenAiCompatErrors
            return id;
        }
        Agent agent = agentService.requireAccess(id, PermissionLevel.RUNNER);
        return agent.getId();
    }

    /**
     * Waits up to {@code first-token-wait-seconds} for the first non-empty token (or completion)
     * before the SSE response is committed. A failure in that window (per-user budget, plan limit,
     * access) is thrown so {@link OpenAiCompatAdvice} can return a real 429/403/404 with
     * Retry-After, as SDKs expect; later failures stay in-band error frames. The wait is bounded
     * well under the edge proxy's ~100 s first-byte limit: a slow agent (long tool call before any
     * text) gets its headers and keepalive comments instead of a 524.
     */
    Flux<String> primeStream(Flux<String> tokens) {
        Sinks.Many<String> sink = Sinks.many().replay().all();
        Disposable sub =
                tokens.filter(t -> t != null && !t.isEmpty())
                        .subscribe(sink::tryEmitNext, sink::tryEmitError, sink::tryEmitComplete);
        Signal<String> first;
        try {
            first =
                    sink.asFlux()
                            .materialize()
                            .next()
                            .timeout(Duration.ofSeconds(firstTokenWaitSeconds), Mono.empty())
                            .block();
        } catch (RuntimeException e) {
            sub.dispose();
            throw e;
        }
        if (first != null && first.isOnError()) {
            sub.dispose();
            Throwable t = first.getThrowable();
            throw t instanceof RuntimeException r ? r : new IllegalStateException(t);
        }
        return sink.asFlux().doOnCancel(sub::dispose);
    }

    /**
     * Bridges the frame stream onto an MVC {@link SseEmitter}; a client disconnect cancels the
     * agent turn. A frame with an {@code event} name is sent as a named SSE event ({@code
     * /v1/responses}); a frame without one is a bare {@code data:} line ({@code
     * /v1/chat/completions}).
     */
    static SseEmitter toEmitter(Flux<ServerSentEvent<String>> events) {
        SseEmitter emitter = new SseEmitter(-1L);
        Disposable sub =
                events.subscribe(
                        ev -> {
                            try {
                                if (ev.comment() != null) {
                                    emitter.send(SseEmitter.event().comment(ev.comment()));
                                } else if (ev.event() != null) {
                                    emitter.send(
                                            SseEmitter.event().name(ev.event()).data(ev.data()));
                                } else {
                                    emitter.send(SseEmitter.event().data(ev.data()));
                                }
                            } catch (IOException e) {
                                throw new UncheckedIOException(e);
                            }
                        },
                        emitter::completeWithError,
                        emitter::complete);
        emitter.onCompletion(sub::dispose);
        emitter.onTimeout(sub::dispose);
        emitter.onError(e -> sub.dispose());
        return emitter;
    }

    /**
     * Same keepalive scheme as the AG-UI endpoint: SSE comment lines while a long tool call is
     * silent.
     */
    static Flux<ServerSentEvent<String>> withHeartbeat(Flux<ServerSentEvent<String>> events) {
        Sinks.Empty<Void> done = Sinks.empty();
        Flux<ServerSentEvent<String>> main = events.doFinally(signal -> done.tryEmitEmpty());
        Flux<ServerSentEvent<String>> heartbeat =
                Flux.interval(HEARTBEAT, HEARTBEAT)
                        .map(tick -> ServerSentEvent.<String>builder().comment("keepalive").build())
                        .takeUntilOther(done.asMono());
        return Flux.merge(main, heartbeat);
    }
}
