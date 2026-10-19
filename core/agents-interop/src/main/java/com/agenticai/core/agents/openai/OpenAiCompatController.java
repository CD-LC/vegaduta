package com.agenticai.core.agents.openai;

import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.dto.AgentResponse;
import com.agenticai.core.agents.turn.AgentChatService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.security.Principal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

/**
 * Inbound OpenAI-compatible surface: lets any OpenAI SDK, LiteLLM, Continue,
 * Open WebUI or plain {@code curl} talk to a platform agent by pointing its
 * base URL at this server. {@code POST /v1/chat/completions} runs a real agent
 * turn (the agent's own system prompt, model, memory, guardrails and
 * server-side tools) and {@code GET /v1/models} lists the caller's agents as
 * models. {@code POST /v1/responses} (Open Responses wire format) is served by
 * {@link OpenResponsesController} on the shared {@link OpenAiCompatCalls}.
 *
 * <h2>Switched OFF by default</h2>
 * The whole surface ({@code controller + advice + rate limiter}) only exists
 * when {@code app.openai-compat.enabled=true}; otherwise {@code /v1/**} is a
 * plain authenticated 404.
 *
 * <h2>Auth, budget, rate limit</h2>
 * {@code /v1/**} accepts EITHER a valid platform JWT (tenant set by {@code
 * TenantContextFilter}, as for {@code /api/**}) OR a long-lived {@code vmcp_} grant key (the
 * SDK's {@code api_key}). {@code SecurityConfig} lets a {@code vmcp_} bearer past the JWT decoder
 * on {@code /v1/**} and {@code McpApiKeyAuthFilter}, registered for {@code /v1/*} in pass-through
 * mode by core/mcpserver's {@code OpenAiCompatKeyAuthConfig}, validates the key, applies the grant's
 * rate limit and sets the tenant. A key caller is confined to the grant's exposed agents
 * ({@link OpenAiCompatCallerScope}); a JWT caller needs RUNNER access to the agent, as on {@code
 * AgentController#chat}. Spend is metered and capped by the per-user budget inside {@link
 * AgentChatService} (the grant is the "user"), which surfaces as a 429 {@code insufficient_quota};
 * request rate is additionally capped by {@link OpenAiCompatRateLimiter} (429 {@code
 * rate_limit_exceeded} + {@code Retry-After}).
 *
 * <h2>Mapping</h2>
 * {@code model} is an agent id (UUID) or an agent's exact name. The platform
 * holds conversation history per session, so by default a call is stateless
 * and earlier {@code messages} are folded into the prompt as a transcript; send
 * an {@code X-Session-Id} header to continue a server-held session instead
 * (then only the last user message is used; the stored id is a hash of tenant,
 * caller and header, so callers never share history).
 * <p>Request-side {@code tools},
 * sampling parameters and non-text content parts are ignored. {@code usage}
 * is an estimate (about four characters per token): the agent chat service
 * streams text and does not surface provider token counts to callers.
 */
@RestController
@ConditionalOnProperty(prefix = "app.openai-compat", name = "enabled", havingValue = "true")
public class OpenAiCompatController {

    static final Duration HEARTBEAT = OpenAiCompatCalls.HEARTBEAT;
    static final String SESSION_HEADER = "X-Session-Id";

    private final ObjectMapper mapper;
    private final OpenAiCompatCalls calls;

    public OpenAiCompatController(
        AgentService agentService,
        AgentChatService agentChatService,
        OpenAiCompatRateLimiter rateLimiter,
        ObjectMapper mapper,
        @Value("${app.openai-compat.max-prompt-chars:16000}") int maxPromptChars,
        @Value("${app.openai-compat.timeout-seconds:300}") long blockingTimeoutSeconds,
        @Value("${app.openai-compat.first-token-wait-seconds:30}") long firstTokenWaitSeconds,
        ObjectProvider<OpenAiCompatCallerScope> callerScope
    ) {
        this.mapper = mapper;
        this.calls = new OpenAiCompatCalls(agentService, agentChatService, rateLimiter, maxPromptChars,
            blockingTimeoutSeconds, firstTokenWaitSeconds, callerScope);
    }

    @GetMapping(value = "/v1/models", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> models() {
        ObjectNode root = mapper.createObjectNode();
        root.put("object", "list");
        ArrayNode data = root.putArray("data");
        for (AgentResponse agent : calls.visibleAgents()) {
            ObjectNode m = data.addObject();
            m.put("id", agent.id().toString());
            m.put("object", "model");
            m.put("created", agent.createdAt() == null ? 0L : agent.createdAt().getEpochSecond());
            m.put("owned_by", "vegaduta");
            m.put("name", agent.name());
        }
        return json(root.toString());
    }

    /**
     * Declared as {@code Object} on purpose. Spring MVC picks the return-value handler from
     * the declared generic type, and a {@code ResponseEntity<?>} carrying a {@code Flux} is
     * handed to the message converters ("No converter ... text/event-stream", HTTP 500)
     * instead of being streamed. Returning the {@link SseEmitter} itself is unambiguous and
     * does not depend on the request's {@code Accept} header (SDKs send application/json).
     */
    @PostMapping(value = "/v1/chat/completions", consumes = MediaType.APPLICATION_JSON_VALUE)
    @SuppressWarnings("unchecked")
    public Object chatCompletions(
        @RequestBody OpenAiChatRequest request,
        @RequestHeader(name = SESSION_HEADER, required = false) String sessionHeader,
        Principal principal
    ) {
        ResponseEntity<?> result = complete(request, sessionHeader, principal);
        if (result.getBody() instanceof Flux<?> events) {
            return OpenAiCompatCalls.toEmitter((Flux<ServerSentEvent<String>>) events);
        }
        return result;
    }

    /** The whole request, with a streaming reply still as a {@code Flux} body (what the unit tests read). */
    ResponseEntity<?> complete(OpenAiChatRequest request, String sessionHeader, Principal principal) {
        OpenAiCompatCalls.Caller caller = calls.authenticate(principal);

        if (!StringUtils.hasText(request.model())) {
            throw new OpenAiCompatException(400, "invalid_request_error", "missing_model",
                "'model' is required: use an agent id or agent name from GET /v1/models");
        }
        boolean serverHistory = StringUtils.hasText(sessionHeader);
        String prompt;
        try {
            prompt = request.toPrompt(serverHistory);
        } catch (IllegalArgumentException badMessages) {
            // toPrompt's messages are written for the client ("'messages' must contain ...").
            throw new OpenAiCompatException(400, "invalid_request_error", "invalid_messages", badMessages.getMessage());
        }
        if (prompt.length() > calls.maxPromptChars()) {
            throw new OpenAiCompatException(400, "invalid_request_error", "context_length_exceeded",
                "The conversation is too long (" + calls.maxPromptChars() + " character maximum).");
        }
        UUID agentId = calls.resolveAgent(request.model().trim(), caller.grant());
        String sessionId = calls.sessionId(caller, sessionHeader);

        Flux<String> tokens = calls.runAgent(agentId, sessionId, caller, prompt);

        String completionId = "chatcmpl-" + UUID.randomUUID().toString().replace("-", "");
        long created = Instant.now().getEpochSecond();
        long promptTokens = estimateTokens(prompt.length());

        if (request.isStream()) {
            return ResponseEntity.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .body(stream(calls.primeStream(tokens), completionId, created, request.model(), promptTokens, request.includeUsage()));
        }

        String content = tokens.collect(Collectors.joining()).block(Duration.ofSeconds(calls.blockingTimeoutSeconds()));
        if (content == null) {
            content = "";
        }
        ObjectNode root = mapper.createObjectNode();
        root.put("id", completionId);
        root.put("object", "chat.completion");
        root.put("created", created);
        root.put("model", request.model());
        ObjectNode choice = root.putArray("choices").addObject();
        choice.put("index", 0);
        ObjectNode message = choice.putObject("message");
        message.put("role", "assistant");
        message.put("content", content);
        choice.put("finish_reason", "stop");
        usage(root.putObject("usage"), promptTokens, estimateTokens(content.length()));
        return json(root.toString());
    }

    static String scopedSessionId(String tenant, String subject, String header) {
        return OpenAiCompatCalls.scopedSessionId(tenant, subject, header);
    }

    private Flux<ServerSentEvent<String>> stream(
        Flux<String> tokens, String id, long created, String model, long promptTokens, boolean includeUsage
    ) {
        AtomicLong completionChars = new AtomicLong();
        Flux<String> frames = Flux.concat(
            Flux.just(chunk(id, created, model, "assistant", "", null)),
            tokens
                .filter(t -> t != null && !t.isEmpty())
                .doOnNext(t -> completionChars.addAndGet(t.length()))
                .map(t -> chunk(id, created, model, null, t, null)),
            Flux.defer(() -> {
                String finish = chunk(id, created, model, null, null, "stop");
                if (!includeUsage) {
                    return Flux.just(finish);
                }
                ObjectNode last = baseChunk(id, created, model);
                last.putArray("choices");
                usage(last.putObject("usage"), promptTokens, estimateTokens(completionChars.get()));
                return Flux.just(finish, last.toString());
            })
        ).onErrorResume(e ->
            // Headers are already committed by the time a mid-stream failure
            // lands, so it rides in-band as an `error` frame, as OpenAI does.
            Flux.just(OpenAiCompatErrors.toJson(mapper, OpenAiCompatErrors.classify(e)))
        ).concatWith(Flux.just("[DONE]"));
        return OpenAiCompatCalls.withHeartbeat(frames.map(data -> ServerSentEvent.<String>builder().data(data).build()));
    }

    private ObjectNode baseChunk(String id, long created, String model) {
        ObjectNode root = mapper.createObjectNode();
        root.put("id", id);
        root.put("object", "chat.completion.chunk");
        root.put("created", created);
        root.put("model", model);
        return root;
    }

    private String chunk(String id, long created, String model, String role, String content, String finishReason) {
        ObjectNode root = baseChunk(id, created, model);
        ObjectNode choice = root.putArray("choices").addObject();
        choice.put("index", 0);
        ObjectNode delta = choice.putObject("delta");
        if (role != null) {
            delta.put("role", role);
        }
        if (content != null) {
            delta.put("content", content);
        }
        if (finishReason == null) {
            choice.putNull("finish_reason");
        } else {
            choice.put("finish_reason", finishReason);
        }
        return root.toString();
    }

    private static void usage(ObjectNode usage, long promptTokens, long completionTokens) {
        usage.put("prompt_tokens", promptTokens);
        usage.put("completion_tokens", completionTokens);
        usage.put("total_tokens", promptTokens + completionTokens);
    }

    static long estimateTokens(long chars) {
        return OpenAiCompatCalls.estimateTokens(chars);
    }

    private static ResponseEntity<String> json(String body) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(body);
    }
}
