package com.agenticai.core.agents.openai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.dto.AgentResponse;
import com.agenticai.core.agents.turn.ChatTurnRequest;
import com.agenticai.core.agents.turn.TurnContext;
import com.agenticai.core.chat.UpstreamProviderException;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** {@link OpenAiCompatController}: responses against the recorded OpenAI wire shapes. */
class OpenAiCompatControllerWireShapesTest extends OpenAiCompatControllerTestSupport {

    // ---- real OpenAI wire shapes (field contract in openai-ref-shapes.json) ----

    private JsonNode shapes() throws Exception {
        try (InputStream in =
                getClass().getResourceAsStream("/openai-compat/openai-ref-shapes.json")) {
            return mapper.readTree(in);
        }
    }

    private void assertShape(JsonNode shapes, String shapeName, JsonNode actual) {
        JsonNode shape = shapes.get(shapeName);
        for (JsonNode required : shape.get("required")) {
            assertTrue(
                    actual.has(required.asText()),
                    shapeName + " missing required '" + required.asText() + "': " + actual);
        }
        java.util.Set<String> allowed = new java.util.HashSet<>();
        shape.get("allowed").forEach(n -> allowed.add(n.asText()));
        actual.fieldNames()
                .forEachRemaining(
                        f ->
                                assertTrue(
                                        allowed.contains(f),
                                        shapeName
                                                + " has field '"
                                                + f
                                                + "' that OpenAI's schema does not define"));
    }

    private OpenAiChatRequest openAiPythonRequest(boolean stream) throws Exception {
        try (InputStream in =
                getClass().getResourceAsStream("/openai-compat/openai-python-request.json")) {
            JsonNode node = mapper.readTree(in);
            ((com.fasterxml.jackson.databind.node.ObjectNode) node).put("stream", stream);
            return mapper.treeToValue(node, OpenAiChatRequest.class);
        }
    }

    @Test
    void sdkStyleRequestWithDeveloperRoleToolMessagesAndImagePartsIsAccepted() throws Exception {
        agentReplies("ok");
        controller.complete(openAiPythonRequest(false), null, principal);
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
        assertTrue(turns.getValue().userMessage().contains("You are a helpful assistant."));
        assertTrue(turns.getValue().userMessage().contains("Tool result: 42"));
        assertTrue(turns.getValue().userMessage().endsWith("Summarise that."));
    }

    @Test
    void nonStreamingResponseMatchesOpenAiChatCompletionSchema() throws Exception {
        agentReplies("Hi");
        JsonNode shapes = shapes();
        JsonNode body =
                mapper.readTree(
                        (String)
                                controller
                                        .complete(openAiPythonRequest(false), null, principal)
                                        .getBody());
        assertShape(shapes, "chat.completion", body);
        assertShape(shapes, "chat.completion.choice", body.get("choices").get(0));
        assertShape(shapes, "chat.completion.message", body.get("choices").get(0).get("message"));
        assertShape(shapes, "usage", body.get("usage"));
        assertTrue(body.get("created").isIntegralNumber());
        assertTrue(body.get("choices").get(0).get("index").isInt());
        assertTrue(body.get("usage").get("total_tokens").isIntegralNumber());
    }

    @Test
    void streamingWireFormatIsDataFramesTerminatedByDoneAndChunksMatchSchema() throws Exception {
        agentReplies("Hel", "lo");
        JsonNode shapes = shapes();
        var result =
                mvc().perform(
                                org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                        .post("/v1/chat/completions")
                                        .principal(principal)
                                        .contentType(
                                                org.springframework.http.MediaType.APPLICATION_JSON)
                                        .content(
                                                mapper.writeValueAsString(
                                                        mapper.readTree(
                                                                getClass()
                                                                        .getResourceAsStream(
                                                                                "/openai-compat/openai-python-request.json")))))
                        .andExpect(
                                org.springframework.test.web.servlet.result.MockMvcResultMatchers
                                        .request()
                                        .asyncStarted())
                        .andReturn();
        result.getAsyncResult(5000);
        String sse = result.getResponse().getContentAsString();

        // SSE wire format an OpenAI SDK parses: "data: <json>" events separated by a blank line,
        // the last one literally "data: [DONE]".
        assertTrue(sse.endsWith("data:[DONE]\n\n") || sse.endsWith("data: [DONE]\n\n"), sse);
        List<String> events =
                java.util.Arrays.stream(sse.split("\n\n")).filter(e -> !e.isBlank()).toList();
        assertTrue(
                events.stream().allMatch(e -> e.startsWith("data:") || e.startsWith(":")),
                events.toString());
        List<JsonNode> chunks =
                events.stream()
                        .filter(e -> e.startsWith("data:"))
                        .map(e -> e.substring(5).trim())
                        .filter(d -> !d.equals("[DONE]"))
                        .map(
                                d -> {
                                    try {
                                        return mapper.readTree(d);
                                    } catch (Exception ex) {
                                        throw new IllegalStateException(d, ex);
                                    }
                                })
                        .toList();
        for (JsonNode c : chunks) {
            assertShape(shapes, "chat.completion.chunk", c);
            assertEquals("chat.completion.chunk", c.get("object").asText());
            if (c.get("choices").size() > 0) {
                assertShape(shapes, "chat.completion.chunk.choice", c.get("choices").get(0));
                assertShape(
                        shapes,
                        "chat.completion.chunk.delta",
                        c.get("choices").get(0).get("delta"));
            }
        }
        // finish_reason is JSON null (key present) until the last content chunk, then "stop".
        assertTrue(chunks.get(0).get("choices").get(0).get("finish_reason").isNull());
        JsonNode usageChunk = chunks.get(chunks.size() - 1);
        assertEquals(0, usageChunk.get("choices").size());
        assertShape(shapes, "usage", usageChunk.get("usage"));
        // every chunk shares one id (SDKs accumulate by it)
        assertEquals(1, chunks.stream().map(c -> c.get("id").asText()).distinct().count());
    }

    @Test
    void errorBodyAndModelListMatchOpenAiSchema() throws Exception {
        JsonNode shapes = shapes();
        JsonNode err =
                mapper.readTree(
                        OpenAiCompatErrors.toJson(
                                mapper,
                                OpenAiCompatErrors.classify(
                                        new UpstreamProviderException(500, "x", "y"))));
        assertShape(shapes, "error", err.get("error"));

        AgentResponse r = mock(AgentResponse.class);
        when(r.id()).thenReturn(AGENT_ID);
        when(r.name()).thenReturn("A");
        when(r.createdAt()).thenReturn(Instant.ofEpochSecond(5L));
        when(agentService.listAll()).thenReturn(List.of(r));
        JsonNode list = mapper.readTree(controller.models().getBody());
        assertShape(shapes, "model.list", list);
        assertShape(shapes, "model", list.get("data").get(0));
    }
}
