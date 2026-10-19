package com.agenticai.core.agents.openai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.budget.PerUserBudgetExhaustedException;
import com.agenticai.core.agents.dto.AgentResponse;
import com.agenticai.core.agents.turn.ChatTurnRequest;
import com.agenticai.core.agents.turn.TurnContext;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;

/** {@link OpenAiCompatController}: chat completions, streaming, models, rate limit. */
class OpenAiCompatControllerChatCompletionsTest extends OpenAiCompatControllerTestSupport {

    @Test
    void streamingOverMvcIsServerSentEventsEvenWhenTheSdkAcceptsJson() throws Exception {
        agentReplies("12", " (4 x 3)");
        var result =
                mvc().perform(
                                org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                        .post("/v1/chat/completions")
                                        .principal(principal)
                                        .contentType(
                                                org.springframework.http.MediaType.APPLICATION_JSON)
                                        .accept(org.springframework.http.MediaType.APPLICATION_JSON)
                                        .content(body(true)))
                        .andExpect(
                                org.springframework.test.web.servlet.result.MockMvcResultMatchers
                                        .request()
                                        .asyncStarted())
                        .andReturn();
        result.getAsyncResult(5000);

        assertEquals(200, result.getResponse().getStatus());
        assertTrue(
                result.getResponse().getContentType().startsWith("text/event-stream"),
                result.getResponse().getContentType());
        String sse = result.getResponse().getContentAsString();
        List<String> frames =
                sse.lines()
                        .filter(l -> l.startsWith("data:"))
                        .map(l -> l.substring(5).trim())
                        .toList();
        assertEquals(
                "assistant",
                mapper.readTree(frames.get(0))
                        .get("choices")
                        .get(0)
                        .get("delta")
                        .get("role")
                        .asText());
        assertEquals(
                "12",
                mapper.readTree(frames.get(1))
                        .get("choices")
                        .get(0)
                        .get("delta")
                        .get("content")
                        .asText());
        assertEquals("[DONE]", frames.get(frames.size() - 1));
    }

    @Test
    void nonStreamingOverMvcIsPlainJson() throws Exception {
        agentReplies("12", " (4 x 3)");
        var result =
                mvc().perform(
                                org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                        .post("/v1/chat/completions")
                                        .principal(principal)
                                        .contentType(
                                                org.springframework.http.MediaType.APPLICATION_JSON)
                                        .content(body(false)))
                        .andReturn();

        assertEquals(200, result.getResponse().getStatus());
        JsonNode json = mapper.readTree(result.getResponse().getContentAsString());
        assertEquals("chat.completion", json.get("object").asText());
        assertEquals(
                "12 (4 x 3)", json.get("choices").get(0).get("message").get("content").asText());
    }

    @Test
    void nonStreamingReturnsChatCompletionShape() throws Exception {
        agentReplies("12", " (4 x 3)");
        ResponseEntity<?> res = controller.complete(fixture(false), null, principal);

        assertEquals(200, res.getStatusCode().value());
        JsonNode body = mapper.readTree((String) res.getBody());
        assertTrue(body.get("id").asText().startsWith("chatcmpl-"));
        assertEquals("chat.completion", body.get("object").asText());
        assertEquals(AGENT_ID.toString(), body.get("model").asText());
        assertTrue(body.get("created").asLong() > 0);
        JsonNode choice = body.get("choices").get(0);
        assertEquals(0, choice.get("index").asInt());
        assertEquals("assistant", choice.get("message").get("role").asText());
        assertEquals("12 (4 x 3)", choice.get("message").get("content").asText());
        assertEquals("stop", choice.get("finish_reason").asText());
        JsonNode usage = body.get("usage");
        assertTrue(usage.get("prompt_tokens").asLong() > 0);
        assertEquals(
                usage.get("prompt_tokens").asLong() + usage.get("completion_tokens").asLong(),
                usage.get("total_tokens").asLong());
    }

    @Test
    void statelessCallFoldsHistoryAndSystemIntoThePrompt() throws Exception {
        agentReplies("ok");
        controller.complete(fixture(false), null, principal);

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
    void sessionHeaderSendsOnlyLastUserMessage() throws Exception {
        agentReplies("ok");
        controller.complete(fixture(false), "abc", principal);

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
    @SuppressWarnings("unchecked")
    void streamingEmitsChunksUsageAndDone() throws Exception {
        agentReplies("Hel", "lo");
        ResponseEntity<?> res = controller.complete(fixture(true), null, principal);

        assertEquals("text/event-stream", res.getHeaders().getContentType().toString());
        List<String> frames =
                ((Flux<ServerSentEvent<String>>) res.getBody())
                        .filter(e -> e.data() != null)
                        .map(ServerSentEvent::data)
                        .collectList()
                        .block(Duration.ofSeconds(5));

        assertEquals("[DONE]", frames.get(frames.size() - 1));
        JsonNode first = mapper.readTree(frames.get(0));
        assertEquals("chat.completion.chunk", first.get("object").asText());
        assertEquals("assistant", first.get("choices").get(0).get("delta").get("role").asText());
        assertEquals(
                "Hel",
                mapper.readTree(frames.get(1))
                        .get("choices")
                        .get(0)
                        .get("delta")
                        .get("content")
                        .asText());
        assertEquals(
                "lo",
                mapper.readTree(frames.get(2))
                        .get("choices")
                        .get(0)
                        .get("delta")
                        .get("content")
                        .asText());
        JsonNode finish = mapper.readTree(frames.get(3));
        assertEquals("stop", finish.get("choices").get(0).get("finish_reason").asText());
        // stream_options.include_usage=true in the fixture -> trailing usage chunk with empty
        // choices
        JsonNode usageChunk = mapper.readTree(frames.get(4));
        assertEquals(0, usageChunk.get("choices").size());
        assertEquals(
                2,
                usageChunk
                        .get("usage")
                        .get("completion_tokens")
                        .asLong()); // "Hello" = 5 chars -> 2 est. tokens
        assertEquals(6, frames.size());
    }

    @Test
    @SuppressWarnings("unchecked")
    void streamFailureBecomesErrorFrameThenDone() throws Exception {
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
                                Flux.just("part"), Flux.error(new IllegalStateException("boom"))));
        ResponseEntity<?> res = controller.complete(fixture(true), null, principal);
        List<String> frames =
                ((Flux<ServerSentEvent<String>>) res.getBody())
                        .filter(e -> e.data() != null)
                        .map(ServerSentEvent::data)
                        .collectList()
                        .block(Duration.ofSeconds(5));
        assertEquals("[DONE]", frames.get(frames.size() - 1));
        JsonNode err = mapper.readTree(frames.get(frames.size() - 2));
        assertEquals("server_error", err.get("error").get("type").asText());
        assertFalse(frames.get(frames.size() - 2).contains("boom"));
    }

    @Test
    void modelResolvesByUniqueNameAndUnknownIs404() throws Exception {
        AgentResponse r = mock(AgentResponse.class);
        when(r.id()).thenReturn(AGENT_ID);
        when(r.name()).thenReturn("Research Helper");
        when(r.createdAt()).thenReturn(Instant.ofEpochSecond(1700000000L));
        when(agentService.listAll()).thenReturn(List.of(r));
        agentReplies("ok");

        OpenAiChatRequest byName =
                new OpenAiChatRequest(
                        "research helper",
                        List.of(
                                new OpenAiChatRequest.Message(
                                        "user", mapper.getNodeFactory().textNode("hi"), null)),
                        false,
                        null);
        assertEquals(200, controller.complete(byName, null, principal).getStatusCode().value());

        OpenAiChatRequest unknown =
                new OpenAiChatRequest(
                        "nope",
                        List.of(
                                new OpenAiChatRequest.Message(
                                        "user", mapper.getNodeFactory().textNode("hi"), null)),
                        false,
                        null);
        OpenAiCompatException ex =
                assertThrows(
                        OpenAiCompatException.class,
                        () -> controller.complete(unknown, null, principal));
        assertEquals(404, OpenAiCompatErrors.classify(ex).status());
        assertEquals("model_not_found", OpenAiCompatErrors.classify(ex).code());
    }

    @Test
    void modelsListsAgents() throws Exception {
        AgentResponse r = mock(AgentResponse.class);
        when(r.id()).thenReturn(AGENT_ID);
        when(r.name()).thenReturn("Research Helper");
        when(r.createdAt()).thenReturn(Instant.ofEpochSecond(1700000000L));
        when(agentService.listAll()).thenReturn(List.of(r));

        JsonNode body = mapper.readTree(controller.models().getBody());
        assertEquals("list", body.get("object").asText());
        JsonNode m = body.get("data").get(0);
        assertEquals(AGENT_ID.toString(), m.get("id").asText());
        assertEquals("model", m.get("object").asText());
        assertEquals(1700000000L, m.get("created").asLong());
        assertEquals("Research Helper", m.get("name").asText());
    }

    @Test
    void budgetExhaustionMapsToRecordedOpenAiErrorBody() throws Exception {
        var info =
                OpenAiCompatErrors.classify(
                        new PerUserBudgetExhaustedException(
                                "Daily text budget reached.",
                                "text",
                                BigDecimal.ONE,
                                BigDecimal.ONE,
                                3600L));
        assertEquals(429, info.status());
        assertEquals(3600L, info.retryAfterSeconds());
        JsonNode actual = mapper.readTree(OpenAiCompatErrors.toJson(mapper, info));
        try (InputStream in =
                getClass()
                        .getResourceAsStream("/openai-compat/sdk-error-insufficient-quota.json")) {
            assertEquals(mapper.readTree(in), actual);
        }
    }

    @Test
    void rateLimitRejectsWith429AndRetryAfter() throws Exception {
        @SuppressWarnings("unchecked")
        ObjectProvider<StringRedisTemplate> noRedis = mock(ObjectProvider.class);
        when(noRedis.getIfAvailable()).thenReturn(null);
        OpenAiCompatController limited =
                new OpenAiCompatController(
                        agentService,
                        chatService,
                        new OpenAiCompatRateLimiter(noRedis, 1, 60),
                        mapper,
                        16000,
                        5,
                        5,
                        noScope());
        agentReplies("ok");
        limited.chatCompletions(fixture(false), null, principal);
        OpenAiCompatException ex =
                assertThrows(
                        OpenAiCompatException.class,
                        () -> limited.chatCompletions(fixture(false), null, principal));
        var info = OpenAiCompatErrors.classify(ex);
        assertEquals(429, info.status());
        assertEquals("rate_limit_exceeded", info.code());
        assertTrue(info.retryAfterSeconds() > 0);
    }

    @Test
    void requestWithoutUserMessageIs400() {
        OpenAiChatRequest bad =
                new OpenAiChatRequest(
                        "x",
                        List.of(
                                new OpenAiChatRequest.Message(
                                        "system",
                                        mapper.getNodeFactory().textNode("only system"),
                                        null)),
                        false,
                        null);
        OpenAiCompatException ex =
                assertThrows(
                        OpenAiCompatException.class,
                        () -> controller.complete(bad, null, principal));
        assertEquals(400, OpenAiCompatErrors.classify(ex).status());
        assertEquals("invalid_messages", OpenAiCompatErrors.classify(ex).code());
    }
}
