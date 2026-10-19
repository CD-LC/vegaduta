package com.agenticai.core.agents.openai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.core.AgentModelNotSupportedException;
import com.agenticai.core.agents.turn.TurnContext;
import com.agenticai.core.chat.UpstreamProviderException;
import com.agenticai.core.chat.gateway.LlmGatewayContentPolicyViolationException;
import com.agenticai.core.chat.gateway.LlmGatewayLimitExceededException;
import com.agenticai.core.providers.NoActiveProviderException;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;

/** {@link OpenAiCompatController}: error mapping, limits and the advice. */
class OpenAiCompatControllerErrorMappingTest extends OpenAiCompatControllerTestSupport {

    // ---- error mapping: provider failures, never a generic 500, never raw exception text ----

    @Test
    void providerFailuresMapToOpenAiStatusesWithoutLeakingDetail() {
        String secret = "sk-live-SECRET stack: at com.vendor.Client.call(Client.java:42)";
        var rateLimited =
                OpenAiCompatErrors.classify(
                        new UpstreamProviderException(429, secret, "slow down"));
        assertEquals(429, rateLimited.status());
        assertEquals("rate_limit_exceeded", rateLimited.code());
        var overloaded =
                OpenAiCompatErrors.classify(new UpstreamProviderException(503, secret, "busy"));
        assertEquals(503, overloaded.status());
        for (int upstream : new int[] {400, 401, 403, 404, 500, 502}) {
            var bad =
                    OpenAiCompatErrors.classify(
                            new UpstreamProviderException(upstream, secret, "oops"));
            // The provider rejecting OUR key is a bad gateway, never the caller's own 401/403.
            assertEquals(502, bad.status(), "upstream " + upstream);
            assertEquals("upstream_error", bad.code());
            assertFalse(bad.message().contains("SECRET"));
            assertFalse(bad.message().contains("com.vendor"));
        }
        assertEquals(503, OpenAiCompatErrors.classify(new NoActiveProviderException()).status());
        assertEquals(
                429,
                OpenAiCompatErrors.classify(new LlmGatewayLimitExceededException(secret)).status());
        var policy =
                OpenAiCompatErrors.classify(new LlmGatewayContentPolicyViolationException(secret));
        assertEquals(400, policy.status());
        assertEquals("content_filter", policy.code());
        assertFalse(policy.message().contains("SECRET"));
        assertEquals(
                400,
                OpenAiCompatErrors.classify(new AgentModelNotSupportedException(secret)).status());
    }

    @Test
    void illegalArgumentTextIsNeverEchoed() {
        var info =
                OpenAiCompatErrors.classify(
                        new IllegalArgumentException(
                                "jdbc:postgresql://db.internal/tenant_7 failed"));
        assertEquals(400, info.status());
        assertFalse(info.message().contains("jdbc"));
        assertFalse(info.message().contains("tenant_7"));
    }

    @Test
    void unexpectedFailureIs500WithGenericBodyAndNoStackTrace() throws Exception {
        var res =
                new OpenAiCompatAdvice(mapper)
                        .handle(
                                new IllegalStateException(
                                        "NPE at com.agenticai.Foo.bar(Foo.java:9)"));
        assertEquals(500, res.getStatusCode().value());
        assertFalse(res.getBody().contains("Foo.java"));
        assertEquals(
                "server_error", mapper.readTree(res.getBody()).get("error").get("type").asText());
    }

    @Test
    void upstreamProviderFailureBeforeFirstTokenIsHttp502OverMvc() throws Exception {
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
                                new UpstreamProviderException(
                                        401, "raw provider body: bad key sk-abc", "consumer")));
        var result =
                mvc().perform(
                                org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                        .post("/v1/chat/completions")
                                        .principal(principal)
                                        .contentType(
                                                org.springframework.http.MediaType.APPLICATION_JSON)
                                        .content(body(false)))
                        .andReturn();
        assertEquals(502, result.getResponse().getStatus());
        String json = result.getResponse().getContentAsString();
        assertFalse(json.contains("sk-abc"));
        assertEquals("upstream_error", mapper.readTree(json).get("error").get("code").asText());
    }

    @Test
    void slowAgentOpensTheStreamAfterTheFirstTokenWaitInsteadOfHoldingTheResponse() {
        OpenAiCompatController quick =
                new OpenAiCompatController(
                        agentService,
                        chatService,
                        new OpenAiCompatRateLimiter(noRedis(), 60, 60),
                        mapper,
                        16000,
                        30,
                        1,
                        noScope());
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
                .thenReturn(Flux.just("late").delaySubscription(Duration.ofSeconds(3)));

        long start = System.nanoTime();
        ResponseEntity<?> res = quick.complete(fixtureUnchecked(true), null, principal);
        long waitedMs = Duration.ofNanos(System.nanoTime() - start).toMillis();

        assertTrue(waitedMs < 2500, "held the response for " + waitedMs + " ms");
        @SuppressWarnings("unchecked")
        List<String> frames =
                ((Flux<ServerSentEvent<String>>) res.getBody())
                        .filter(e -> e.data() != null)
                        .map(ServerSentEvent::data)
                        .collectList()
                        .block(Duration.ofSeconds(10));
        assertTrue(frames.stream().anyMatch(f -> f.contains("\"late\"")), frames.toString());
        assertEquals("[DONE]", frames.get(frames.size() - 1));
    }

    @Test
    void promptOverLimitIs400ContextLengthExceeded() {
        OpenAiCompatController tiny =
                new OpenAiCompatController(
                        agentService,
                        chatService,
                        new OpenAiCompatRateLimiter(noRedis(), 60, 60),
                        mapper,
                        10,
                        5,
                        5,
                        noScope());
        OpenAiChatRequest big =
                new OpenAiChatRequest(
                        AGENT_ID.toString(),
                        List.of(
                                new OpenAiChatRequest.Message(
                                        "user",
                                        mapper.getNodeFactory()
                                                .textNode(
                                                        "this prompt is longer than ten"
                                                                + " characters"),
                                        null)),
                        false,
                        null);
        OpenAiCompatException ex =
                assertThrows(
                        OpenAiCompatException.class,
                        () -> tiny.chatCompletions(big, null, principal));
        assertEquals(400, OpenAiCompatErrors.classify(ex).status());
        assertEquals("context_length_exceeded", OpenAiCompatErrors.classify(ex).code());
    }

    @Test
    void invalidJsonBodyIs400InvalidJson() {
        var info =
                OpenAiCompatErrors.classify(
                        new org.springframework.http.converter.HttpMessageNotReadableException(
                                "bad", (org.springframework.http.HttpInputMessage) null));
        assertEquals(400, info.status());
        assertEquals("invalid_json", info.code());
    }

    @Test
    void modelsFailureIsClassifiedAs500WithoutLeakingDetail() {
        when(agentService.listAll()).thenThrow(new IllegalStateException("db down"));
        IllegalStateException ex =
                assertThrows(IllegalStateException.class, () -> controller.models());
        var info = OpenAiCompatErrors.classify(ex);
        assertEquals(500, info.status());
        assertFalse(info.message().contains("db down"));
    }

    @Test
    void advicePreservesStatusAndRetryAfter() throws Exception {
        var res =
                new OpenAiCompatAdvice(mapper)
                        .handle(
                                new OpenAiCompatException(
                                        429,
                                        "rate_limit_exceeded",
                                        "rate_limit_exceeded",
                                        "slow down",
                                        7L));
        assertEquals(429, res.getStatusCode().value());
        assertEquals("7", res.getHeaders().getFirst("Retry-After"));
        assertEquals(
                "rate_limit_exceeded",
                mapper.readTree(res.getBody()).get("error").get("code").asText());
    }
}
