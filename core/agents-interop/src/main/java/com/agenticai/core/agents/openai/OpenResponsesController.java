package com.agenticai.core.agents.openai;

import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.turn.AgentChatService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.Principal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

/**
 * Open Responses / OpenAI Responses compatible {@code POST /v1/responses}: the same real agent turn
 * as {@code POST /v1/chat/completions} ({@link OpenAiCompatController}), in the Responses wire
 * format. Request mapping is {@link OpenResponsesInputMapper}, JSON/event shapes are {@link
 * OpenResponsesWire}.
 *
 * <p>Same kill switch ({@code app.openai-compat.enabled}, default false: the bean does not exist
 * and {@code /v1/**} is a plain authenticated 404), same auth ({@code vmcp_} grant key or platform
 * JWT, enforced by SecurityConfig and the mcpserver key filter for {@code /v1/**}), same per-caller
 * rate limit, same per-user budget metering (inside {@link AgentChatService}, subject {@code
 * mcp-external:<grantId>} for a grant key), same agent resolution and grant scoping, and the same
 * OpenAI error envelope ({@link OpenAiCompatAdvice}). All of that lives in the shared {@link
 * OpenAiCompatCalls}.
 *
 * <p>Responses are not stored: {@code previous_response_id} is rejected (400 {@code
 * unsupported_parameter}, see {@link OpenResponsesInputMapper}) and {@code GET /v1/responses/{id}}
 * is a 404. {@code usage} is an estimate (about four characters per token), as on chat completions.
 */
@RestController
@ConditionalOnProperty(prefix = "app.openai-compat", name = "enabled", havingValue = "true")
public class OpenResponsesController {

    private final ObjectMapper mapper;
    private final OpenAiCompatCalls calls;

    public OpenResponsesController(
            AgentService agentService,
            AgentChatService agentChatService,
            OpenAiCompatRateLimiter rateLimiter,
            ObjectMapper mapper,
            @Value("${app.openai-compat.max-prompt-chars:16000}") int maxPromptChars,
            @Value("${app.openai-compat.timeout-seconds:300}") long blockingTimeoutSeconds,
            @Value("${app.openai-compat.first-token-wait-seconds:30}") long firstTokenWaitSeconds,
            ObjectProvider<OpenAiCompatCallerScope> callerScope) {
        this.mapper = mapper;
        this.calls =
                new OpenAiCompatCalls(
                        agentService,
                        agentChatService,
                        rateLimiter,
                        maxPromptChars,
                        blockingTimeoutSeconds,
                        firstTokenWaitSeconds,
                        callerScope);
    }

    /**
     * Declared as {@code Object} for the same reason as {@code
     * OpenAiCompatController#chatCompletions}: a streaming reply must be returned as the {@code
     * SseEmitter} itself, independent of the request's {@code Accept} header.
     */
    @PostMapping(value = "/v1/responses", consumes = MediaType.APPLICATION_JSON_VALUE)
    @SuppressWarnings("unchecked")
    public Object create(
            @RequestBody OpenResponsesRequest request,
            @RequestHeader(name = OpenAiCompatController.SESSION_HEADER, required = false)
                    String sessionHeader,
            Principal principal) {
        ResponseEntity<?> result = respond(request, sessionHeader, principal);
        if (result.getBody() instanceof Flux<?> events) {
            return OpenAiCompatCalls.toEmitter((Flux<ServerSentEvent<String>>) events);
        }
        return result;
    }

    /**
     * Responses are not stored, so there is nothing to retrieve. An explicit OpenAI-shaped 404
     * rather than pretending (or falling through to a framework error page).
     */
    @GetMapping(value = "/v1/responses/{responseId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> retrieve(@PathVariable("responseId") String responseId) {
        throw new OpenAiCompatException(
                404,
                "invalid_request_error",
                "response_not_found",
                "Responses are not stored by this server, so a response cannot be retrieved by id.");
    }

    /**
     * The whole request, with a streaming reply still as a {@code Flux} body (what the unit tests
     * read).
     */
    ResponseEntity<?> respond(
            OpenResponsesRequest request, String sessionHeader, Principal principal) {
        OpenAiCompatCalls.Caller caller = calls.authenticate(principal);

        if (!StringUtils.hasText(request.model())) {
            throw new OpenAiCompatException(
                    400,
                    "invalid_request_error",
                    "missing_model",
                    "'model' is required: use an agent id or agent name from GET /v1/models");
        }
        OpenAiChatRequest chat = OpenResponsesInputMapper.toChatRequest(request);
        String prompt;
        try {
            prompt = chat.toPrompt(StringUtils.hasText(sessionHeader));
        } catch (IllegalArgumentException badInput) {
            throw new OpenAiCompatException(
                    400, "invalid_request_error", "invalid_request", badInput.getMessage());
        }
        if (prompt.length() > calls.maxPromptChars()) {
            throw new OpenAiCompatException(
                    400,
                    "invalid_request_error",
                    "context_length_exceeded",
                    "The conversation is too long ("
                            + calls.maxPromptChars()
                            + " character maximum).");
        }
        UUID agentId = calls.resolveAgent(request.model().trim(), caller.grant());
        String sessionId = calls.sessionId(caller, sessionHeader);

        Flux<String> tokens = calls.runAgent(agentId, sessionId, caller, prompt);

        OpenResponsesWire wire =
                new OpenResponsesWire(
                        mapper,
                        new OpenResponsesWire.Meta(
                                "resp_" + UUID.randomUUID().toString().replace("-", ""),
                                "msg_" + UUID.randomUUID().toString().replace("-", ""),
                                Instant.now().getEpochSecond(),
                                request.model(),
                                OpenResponsesInputMapper.instructionsText(request.instructions()),
                                request.metadata()));
        long inputTokens = OpenAiCompatCalls.estimateTokens(prompt.length());

        if (request.isStream()) {
            Flux<ServerSentEvent<String>> events =
                    wire.events(calls.primeStream(tokens), inputTokens);
            return ResponseEntity.ok()
                    .contentType(MediaType.TEXT_EVENT_STREAM)
                    .body(OpenAiCompatCalls.withHeartbeat(events));
        }

        String content =
                tokens.collect(Collectors.joining())
                        .block(Duration.ofSeconds(calls.blockingTimeoutSeconds()));
        if (content == null) {
            content = "";
        }
        String body =
                wire.response(
                                "completed",
                                content,
                                inputTokens,
                                OpenAiCompatCalls.estimateTokens(content.length()),
                                Instant.now().getEpochSecond(),
                                null)
                        .toString();
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(body);
    }
}
