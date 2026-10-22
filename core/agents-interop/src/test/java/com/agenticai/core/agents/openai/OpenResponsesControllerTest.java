package com.agenticai.core.agents.openai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.budget.PerUserBudgetExhaustedException;
import com.agenticai.core.agents.core.Agent;
import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.turn.AgentChatService;
import com.agenticai.core.agents.turn.ChatTurnRequest;
import com.agenticai.core.agents.turn.TurnContext;
import com.agenticai.core.sharing.PermissionLevel;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import reactor.core.publisher.Flux;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Drives {@link OpenResponsesController} with a recorded OpenAI-SDK-style Responses request ({@code
 * open-responses/sdk-request.json}) and compares the reply with recorded fixtures ({@code
 * expected-response.json}, {@code expected-stream-events.jsonl}) after normalising the generated
 * ids and timestamps. Spec: https://www.openresponses.org/specification and
 * https://platform.openai.com/docs/api-reference/responses.
 */
class OpenResponsesControllerTest {

    private static final UUID AGENT_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");

    private final ObjectMapper mapper = com.agenticai.core.json.JsonMappers.plain();
    private AgentService agentService;
    private AgentChatService chatService;
    private OpenResponsesController controller;
    private Principal principal;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        agentService = mock(AgentService.class);
        chatService = mock(AgentChatService.class);
        Agent agent = mock(Agent.class);
        when(agent.getId()).thenReturn(AGENT_ID);
        when(agentService.requireAccess(any(UUID.class), eq(PermissionLevel.RUNNER)))
                .thenReturn(agent);
        controller = controller(60, 16000, scopeOf(null));
        principal = () -> "user-1";
    }

    @SuppressWarnings("unchecked")
    private OpenResponsesController controller(
            int rateMax, int maxPromptChars, ObjectProvider<OpenAiCompatCallerScope> scope) {
        ObjectProvider<StringRedisTemplate> noRedis = mock(ObjectProvider.class);
        when(noRedis.getIfAvailable()).thenReturn(null);
        return new OpenResponsesController(
                agentService,
                chatService,
                new OpenAiCompatRateLimiter(noRedis, rateMax, 60),
                mapper,
                maxPromptChars,
                5,
                5,
                scope);
    }

    @SuppressWarnings("unchecked")
    private ObjectProvider<OpenAiCompatCallerScope> scopeOf(Set<UUID> exposed) {
        ObjectProvider<OpenAiCompatCallerScope> p = mock(ObjectProvider.class);
        OpenAiCompatCallerScope scope =
                exposed == null ? null : () -> java.util.Optional.of(exposed);
        when(p.getIfAvailable()).thenReturn(scope);
        return p;
    }

    private JsonNode resource(String name) throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/open-responses/" + name)) {
            return mapper.readTree(in);
        }
    }

    private String requestBody(boolean stream) throws Exception {
        ObjectNode node = (ObjectNode) resource("sdk-request.json");
        node.put("stream", stream);
        return node.toString();
    }

    private OpenResponsesRequest request(boolean stream) throws Exception {
        return mapper.readValue(requestBody(stream), OpenResponsesRequest.class);
    }

    private void agentReplies(String... tokens) {
        when(chatService.chat(
                        any(TurnContext.class),
                        argThat(
                                r ->
                                        r.sessionId() != null
                                                && r.subjectKey() != null
                                                && r.userMessage() != null
                                                && r.attachmentKey() == null
                                                && r.attachmentMimeType() == null
                                                && r.inlineAttachments() == null
                                                && r.extraTools() != null
                                                && r.turnRef() == null
                                                && r.visionSessionId() == null)))
                .thenReturn(Flux.just(tokens));
    }

    private MockMvc mvc() {
        return MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new OpenAiCompatAdvice(mapper))
                .build();
    }

    /**
     * Replaces the generated ids and timestamps so a reply can be compared with a recorded fixture.
     */
    private JsonNode normalize(JsonNode node) {
        JsonNode copy = node.deepCopy();
        normalizeInPlace(copy);
        return copy;
    }

    private void normalizeInPlace(JsonNode node) {
        if (node.isObject()) {
            ObjectNode obj = (ObjectNode) node;
            Iterator<Map.Entry<String, JsonNode>> it = obj.properties().iterator();
            List<String> names = new ArrayList<>();
            while (it.hasNext()) {
                names.add(it.next().getKey());
            }
            for (String name : names) {
                JsonNode v = obj.get(name);
                if ("id".equals(name) && v.isString() && v.asString().startsWith("resp_")) {
                    obj.put(name, "RESP");
                } else if ("id".equals(name) && v.isString() && v.asString().startsWith("msg_")) {
                    obj.put(name, "MSG");
                } else if ("item_id".equals(name)) {
                    obj.put(name, "MSG");
                } else if (("created_at".equals(name) || "completed_at".equals(name))
                        && v.isNumber()) {
                    obj.put(name, 0L);
                } else if (v.isIntegralNumber()) {
                    // IntNode vs LongNode are never equal in Jackson: compare every integer as a
                    // long.
                    obj.put(name, v.asLong());
                } else {
                    normalizeInPlace(v);
                }
            }
        } else if (node.isArray()) {
            node.forEach(this::normalizeInPlace);
        }
    }

    @SuppressWarnings("unchecked")
    private List<ServerSentEvent<String>> events(ResponseEntity<?> res) {
        return ((Flux<ServerSentEvent<String>>) res.getBody())
                .filter(e -> e.data() != null)
                .collectList()
                .block(Duration.ofSeconds(5));
    }

    // ---- non-streaming ----

    @Test
    void nonStreamingMatchesTheRecordedResponse() throws Exception {
        agentReplies("12", " (4 x 3)");
        ResponseEntity<?> res = controller.respond(request(false), null, principal);

        assertEquals(200, res.getStatusCode().value());
        JsonNode body = mapper.readTree((String) res.getBody());
        assertTrue(body.get("id").asString().startsWith("resp_"));
        assertTrue(body.get("output").get(0).get("id").asString().startsWith("msg_"));
        assertTrue(body.get("created_at").asLong() > 0);
        assertEquals(normalize(resource("expected-response.json")), normalize(body));
    }

    @Test
    void statelessCallFoldsInstructionsAndHistoryIntoThePrompt() throws Exception {
        agentReplies("ok");
        controller.respond(request(false), null, principal);

        ArgumentCaptor<ChatTurnRequest> turns = ArgumentCaptor.forClass(ChatTurnRequest.class);
        org.mockito.Mockito.verify(chatService).chat(any(TurnContext.class), turns.capture());
        org.junit.jupiter.api.Assertions.assertTrue(
                turns.getAllValues().stream()
                        .allMatch(
                                r ->
                                        java.util.Objects.equals(r.agentId(), AGENT_ID)
                                                && r.sessionId() != null
                                                && java.util.Objects.equals(
                                                        r.subjectKey(), "user-1")
                                                && r.attachmentKey() == null
                                                && r.attachmentMimeType() == null
                                                && r.inlineAttachments() == null
                                                && r.extraTools() != null
                                                && r.turnRef() == null
                                                && r.visionSessionId() == null
                                                && r.forceFastDepth() == false));
        String p = turns.getValue().userMessage();
        assertTrue(p.contains("You are terse."));
        assertTrue(p.contains("User: What is 2+2?"));
        assertTrue(p.contains("Assistant: 4"));
        assertTrue(p.endsWith("And times three?"));
    }

    @Test
    void sessionHeaderSendsOnlyTheLastUserMessageUnderAScopedSessionId() throws Exception {
        agentReplies("ok");
        controller.respond(request(false), "abc", principal);

        ArgumentCaptor<ChatTurnRequest> turns2 = ArgumentCaptor.forClass(ChatTurnRequest.class);
        org.mockito.Mockito.verify(chatService).chat(any(TurnContext.class), turns2.capture());
        org.junit.jupiter.api.Assertions.assertTrue(
                turns2.getAllValues().stream()
                        .allMatch(
                                r ->
                                        java.util.Objects.equals(r.agentId(), AGENT_ID)
                                                && java.util.Objects.equals(
                                                        r.subjectKey(), "user-1")
                                                && r.attachmentKey() == null
                                                && r.attachmentMimeType() == null
                                                && r.inlineAttachments() == null
                                                && r.extraTools() != null
                                                && r.turnRef() == null
                                                && r.visionSessionId() == null
                                                && r.forceFastDepth() == false));
        assertTrue(turns2.getValue().sessionId().matches("oai-[0-9a-f]{32}"));
        assertFalse(turns2.getValue().sessionId().contains("abc"));
        assertEquals("And times three?", turns2.getValue().userMessage());
    }

    @Test
    void nonStreamingOverMvcIsPlainJson() throws Exception {
        agentReplies("12", " (4 x 3)");
        MvcResult result =
                mvc().perform(
                                MockMvcRequestBuilders.post("/v1/responses")
                                        .principal(principal)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(requestBody(false)))
                        .andReturn();
        assertEquals(200, result.getResponse().getStatus());
        JsonNode json = mapper.readTree(result.getResponse().getContentAsString());
        assertEquals("response", json.get("object").asString());
        assertEquals(
                "12 (4 x 3)",
                json.get("output").get(0).get("content").get(0).get("text").asString());
    }

    // ---- streaming ----

    @Test
    void streamingMatchesTheRecordedEventSequence() throws Exception {
        agentReplies("Hel", "lo");
        ResponseEntity<?> res = controller.respond(request(true), null, principal);
        assertEquals("text/event-stream", res.getHeaders().getContentType().toString());

        List<ServerSentEvent<String>> frames = events(res);
        assertEquals("[DONE]", frames.get(frames.size() - 1).data());
        assertNull(frames.get(frames.size() - 1).event());

        List<String> recorded =
                new String(
                                getClass()
                                        .getResourceAsStream(
                                                "/open-responses/expected-stream-events.jsonl")
                                        .readAllBytes(),
                                StandardCharsets.UTF_8)
                        .lines()
                        .filter(l -> !l.isBlank())
                        .toList();
        assertEquals(recorded.size(), frames.size() - 1);
        for (int i = 0; i < recorded.size(); i++) {
            JsonNode actual = mapper.readTree(frames.get(i).data());
            assertEquals(
                    normalize(mapper.readTree(recorded.get(i))), normalize(actual), "event #" + i);
            // the SSE event name is the payload type, and sequence_number counts 0,1,2,...
            assertEquals(actual.get("type").asString(), frames.get(i).event());
            assertEquals(i, actual.get("sequence_number").asInt());
        }
    }

    @Test
    void streamingOverMvcSendsNamedSseEventsEvenWhenTheSdkAcceptsJson() throws Exception {
        agentReplies("Hel", "lo");
        MvcResult result =
                mvc().perform(
                                MockMvcRequestBuilders.post("/v1/responses")
                                        .principal(principal)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .accept(MediaType.APPLICATION_JSON)
                                        .content(requestBody(true)))
                        .andExpect(MockMvcResultMatchers.request().asyncStarted())
                        .andReturn();
        result.getAsyncResult(5000);

        assertEquals(200, result.getResponse().getStatus());
        assertTrue(
                result.getResponse().getContentType().startsWith("text/event-stream"),
                result.getResponse().getContentType());
        String sse = result.getResponse().getContentAsString();
        List<String> names =
                sse.lines()
                        .filter(l -> l.startsWith("event:"))
                        .map(l -> l.substring(6).trim())
                        .toList();
        assertEquals(
                List.of(
                        "response.created",
                        "response.in_progress",
                        "response.output_item.added",
                        "response.content_part.added",
                        "response.output_text.delta",
                        "response.output_text.delta",
                        "response.output_text.done",
                        "response.content_part.done",
                        "response.output_item.done",
                        "response.completed"),
                names);
        List<String> data =
                sse.lines()
                        .filter(l -> l.startsWith("data:"))
                        .map(l -> l.substring(5).trim())
                        .toList();
        assertEquals("[DONE]", data.get(data.size() - 1));
        assertEquals(
                "Hello",
                mapper.readTree(data.get(data.size() - 2))
                        .get("response")
                        .get("output")
                        .get(0)
                        .get("content")
                        .get(0)
                        .get("text")
                        .asString());
    }

    @Test
    void midStreamFailureIsAnErrorEventThenResponseFailedThenDoneWithoutLeakingDetail()
            throws Exception {
        when(chatService.chat(
                        any(TurnContext.class),
                        argThat(
                                r ->
                                        r.sessionId() != null
                                                && r.subjectKey() != null
                                                && r.userMessage() != null
                                                && r.attachmentKey() == null
                                                && r.attachmentMimeType() == null
                                                && r.inlineAttachments() == null
                                                && r.extraTools() != null
                                                && r.turnRef() == null
                                                && r.visionSessionId() == null)))
                .thenReturn(
                        Flux.concat(
                                Flux.just("part"),
                                Flux.error(new IllegalStateException("boom jdbc://internal"))));
        List<ServerSentEvent<String>> frames =
                events(controller.respond(request(true), null, principal));

        assertEquals("[DONE]", frames.get(frames.size() - 1).data());
        JsonNode failed = mapper.readTree(frames.get(frames.size() - 2).data());
        JsonNode error = mapper.readTree(frames.get(frames.size() - 3).data());
        assertEquals("response.failed", failed.get("type").asString());
        assertEquals("failed", failed.get("response").get("status").asString());
        assertEquals("server_error", failed.get("response").get("error").get("code").asString());
        assertEquals("error", error.get("type").asString());
        assertEquals("server_error", error.get("code").asString());
        assertEquals(
                failed.get("sequence_number").asInt() - 1, error.get("sequence_number").asInt());
        for (ServerSentEvent<String> f : frames) {
            assertFalse(f.data().contains("boom"));
            assertFalse(f.data().contains("jdbc"));
        }
    }

    @Test
    void emptyAgentReplyStillCompletesWithEmptyText() throws Exception {
        agentReplies();
        List<ServerSentEvent<String>> frames =
                events(controller.respond(request(true), null, principal));
        JsonNode done = mapper.readTree(frames.get(frames.size() - 2).data());
        assertEquals("response.completed", done.get("type").asString());
        assertEquals(
                "",
                done.get("response")
                        .get("output")
                        .get(0)
                        .get("content")
                        .get(0)
                        .get("text")
                        .asString());
    }

    // ---- errors: same envelope, same gates as chat completions ----

    @Test
    void budgetErrorBeforeTheFirstTokenIsHttp429WithRetryAfterOverMvc() throws Exception {
        when(chatService.chat(
                        any(TurnContext.class),
                        argThat(
                                r ->
                                        r.sessionId() != null
                                                && r.subjectKey() != null
                                                && r.userMessage() != null
                                                && r.attachmentKey() == null
                                                && r.attachmentMimeType() == null
                                                && r.inlineAttachments() == null
                                                && r.extraTools() != null
                                                && r.turnRef() == null
                                                && r.visionSessionId() == null)))
                .thenReturn(
                        Flux.error(
                                new PerUserBudgetExhaustedException(
                                        "Daily text budget reached.",
                                        "text",
                                        BigDecimal.ONE,
                                        BigDecimal.ONE,
                                        3600L)));
        MvcResult result =
                mvc().perform(
                                MockMvcRequestBuilders.post("/v1/responses")
                                        .principal(principal)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(requestBody(true)))
                        .andReturn();
        assertEquals(429, result.getResponse().getStatus());
        assertEquals("3600", result.getResponse().getHeader("Retry-After"));
        assertEquals(
                "insufficient_quota",
                mapper.readTree(result.getResponse().getContentAsString())
                        .get("error")
                        .get("code")
                        .asString());
    }

    @Test
    void callerWithNeitherPrincipalNorGrantIs401AndNoAgentRuns() throws Exception {
        OpenAiCompatException ex =
                assertThrows(
                        OpenAiCompatException.class,
                        () -> controller.respond(request(false), null, null));
        assertEquals(401, OpenAiCompatErrors.classify(ex).status());
        assertEquals("invalid_api_key", OpenAiCompatErrors.classify(ex).code());
        org.mockito.Mockito.verifyNoInteractions(chatService);
    }

    @Test
    void rateLimitSharesTheChatCompletionsLimiterBehaviour() throws Exception {
        OpenResponsesController limited = controller(1, 16000, scopeOf(null));
        agentReplies("ok");
        limited.respond(request(false), null, principal);
        OpenAiCompatException ex =
                assertThrows(
                        OpenAiCompatException.class,
                        () -> limited.respond(request(false), null, principal));
        var info = OpenAiCompatErrors.classify(ex);
        assertEquals(429, info.status());
        assertEquals("rate_limit_exceeded", info.code());
        assertTrue(info.retryAfterSeconds() > 0);
    }

    @Test
    void missingModelIs400AndUnknownModelIs404() throws Exception {
        OpenResponsesRequest noModel =
                new OpenResponsesRequest(
                        null,
                        mapper.getNodeFactory().textNode("hi"),
                        null,
                        null,
                        null,
                        false,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null);
        assertEquals(
                "missing_model",
                OpenAiCompatErrors.classify(
                                assertThrows(
                                        OpenAiCompatException.class,
                                        () -> controller.respond(noModel, null, principal)))
                        .code());

        OpenResponsesRequest unknown =
                new OpenResponsesRequest(
                        "nope",
                        mapper.getNodeFactory().textNode("hi"),
                        null,
                        null,
                        null,
                        false,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null);
        var info =
                OpenAiCompatErrors.classify(
                        assertThrows(
                                OpenAiCompatException.class,
                                () -> controller.respond(unknown, null, principal)));
        assertEquals(404, info.status());
        assertEquals("model_not_found", info.code());
    }

    @Test
    void oversizedPromptIs400() throws Exception {
        OpenResponsesController tiny = controller(60, 10, scopeOf(null));
        OpenResponsesRequest big =
                new OpenResponsesRequest(
                        AGENT_ID.toString(),
                        mapper.getNodeFactory()
                                .textNode("this prompt is longer than ten characters"),
                        null,
                        null,
                        null,
                        false,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null);
        var info =
                OpenAiCompatErrors.classify(
                        assertThrows(
                                OpenAiCompatException.class,
                                () -> tiny.respond(big, null, principal)));
        assertEquals(400, info.status());
        assertEquals("context_length_exceeded", info.code());
    }

    @Test
    void grantKeyCallerCannotReachAnAgentOutsideItsGrant() {
        OpenResponsesController c = controller(60, 16000, scopeOf(Set.of(UUID.randomUUID())));
        com.agenticai.core.security.TenantContext.set(
                com.agenticai.core.security.TenantContext.of(
                        "tenant_a", "mcp-external:g1", Set.of("ROLE_mcp-external")));
        try {
            OpenAiCompatException ex =
                    assertThrows(
                            OpenAiCompatException.class,
                            () -> c.respond(request(false), null, null));
            assertEquals(404, OpenAiCompatErrors.classify(ex).status());
        } finally {
            com.agenticai.core.security.TenantContext.clear();
        }
        org.mockito.Mockito.verifyNoInteractions(chatService);
    }

    @Test
    void previousResponseIdIsRejectedNotSilentlyIgnoredOverMvc() throws Exception {
        ObjectNode body = (ObjectNode) mapper.readTree(requestBody(false));
        body.put("previous_response_id", "resp_abc");
        MvcResult result =
                mvc().perform(
                                MockMvcRequestBuilders.post("/v1/responses")
                                        .principal(principal)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(body.toString()))
                        .andReturn();
        assertEquals(400, result.getResponse().getStatus());
        JsonNode err = mapper.readTree(result.getResponse().getContentAsString()).get("error");
        assertEquals("unsupported_parameter", err.get("code").asString());
        assertEquals("invalid_request_error", err.get("type").asString());
        assertTrue(err.get("message").asString().contains("previous_response_id"));
        org.mockito.Mockito.verifyNoInteractions(chatService);
    }

    @Test
    void malformedJsonBodyIs400InvalidJsonOverMvc() throws Exception {
        MvcResult result =
                mvc().perform(
                                MockMvcRequestBuilders.post("/v1/responses")
                                        .principal(principal)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{not json"))
                        .andReturn();
        assertEquals(400, result.getResponse().getStatus());
        assertEquals(
                "invalid_json",
                mapper.readTree(result.getResponse().getContentAsString())
                        .get("error")
                        .get("code")
                        .asString());
    }

    @Test
    void wrongTypedFieldIs400OverMvc() throws Exception {
        MvcResult result =
                mvc().perform(
                                MockMvcRequestBuilders.post("/v1/responses")
                                        .principal(principal)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                "{\"model\":\""
                                                        + AGENT_ID
                                                        + "\",\"input\":\"hi\",\"max_output_tokens\":\"many\"}"))
                        .andReturn();
        assertEquals(400, result.getResponse().getStatus());
    }

    @Test
    void retrievingAStoredResponseIsAnExplicitOpenAiShaped404OverMvc() throws Exception {
        MvcResult result =
                mvc().perform(
                                MockMvcRequestBuilders.get("/v1/responses/resp_123")
                                        .principal(principal))
                        .andReturn();
        assertEquals(404, result.getResponse().getStatus());
        JsonNode err = mapper.readTree(result.getResponse().getContentAsString()).get("error");
        assertEquals("response_not_found", err.get("code").asString());
        assertEquals("invalid_request_error", err.get("type").asString());
    }

    @Test
    void chatCompletionsAdviceStillCoversTheOriginalController() {
        // OpenAiCompatAdvice is scoped by assignableTypes; both controllers must stay in it.
        var annotation =
                OpenAiCompatAdvice.class.getAnnotation(
                        org.springframework.web.bind.annotation.RestControllerAdvice.class);
        assertEquals(
                Set.of(OpenAiCompatController.class, OpenResponsesController.class),
                Set.of(annotation.assignableTypes()));
    }
}
