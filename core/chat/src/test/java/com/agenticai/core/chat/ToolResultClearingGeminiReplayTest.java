package com.agenticai.core.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.genai.types.Part;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;

/**
 * The placeholder that replaces an old tool result must survive Spring AI's Gemini message
 * conversion. {@code GoogleGenAiChatModel#parseJsonToMap} (package-private path used by {@code
 * messageToGeminiParts}) parses every tool-response body as JSON and throws on anything else, which
 * killed the whole turn as a provider error - and the hosted pool is Gemini-first, so this was on the
 * default path as soon as one turn read more than the clearing threshold of tool output.
 *
 * <p>This runs the REAL conversion (reflection into the Spring AI method; no network): the exact code
 * the next request of the tool loop goes through.
 */
class ToolResultClearingGeminiReplayTest {

    private static List<Part> toGeminiParts(Message message) throws Exception {
        Method convert = GoogleGenAiChatModel.class.getDeclaredMethod("messageToGeminiParts", Message.class);
        convert.setAccessible(true);
        try {
            @SuppressWarnings("unchecked")
            List<Part> parts = (List<Part>) convert.invoke(null, message);
            return parts;
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw e;
        }
    }

    private static List<Message> bigHistory() {
        List<Message> history = new ArrayList<>();
        history.add(new UserMessage("research this"));
        for (int i = 1; i <= 6; i++) {
            history.add(AssistantMessage.builder().content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall("id-" + i, "function", "web_search", "{}"))).build());
            history.add(ToolResponseMessage.builder().responses(List.of(
                new ToolResponseMessage.ToolResponse("id-" + i, "web_search", "r".repeat(10_000)))).build());
        }
        return history;
    }

    @Test
    void everyClearedPlaceholderConvertsToAGeminiFunctionResponseWithoutThrowing() throws Exception {
        List<Message> cleared = new ToolResultClearing(true, 24_000, 3).apply(bigHistory());

        int placeholders = 0;
        for (Message message : cleared) {
            if (!(message instanceof ToolResponseMessage tool)
                || tool.getResponses().get(0).responseData().length() > ToolResultClearing.MAX_PLACEHOLDER_CHARS) {
                // Only the placeholders are under test; a real tool result reaches the adapter already
                // wrapped as JSON by the agents-side wrapper, which this module does not have.
                continue;
            }
            List<Part> parts = toGeminiParts(tool);
            assertEquals(1, parts.size());
            Map<String, Object> response = parts.get(0).functionResponse().orElseThrow().response().orElseThrow();
            if (response.containsKey("context_cleared")) {
                placeholders++;
                assertEquals(Boolean.TRUE, response.get("context_cleared"));
                assertEquals("web_search", response.get("tool"));
                assertNotNull(response.get("restore"), "tells the model how to get the result back");
            }
        }
        assertEquals(3, placeholders, "the three oldest results were cleared");
    }

    @Test
    void thePlaceholderIsAJsonObjectThatNamesTheToolAndTheOriginalSize() throws Exception {
        List<Message> cleared = new ToolResultClearing(true, 24_000, 3).apply(bigHistory());
        String placeholder = ((ToolResponseMessage) cleared.get(2)).getResponses().get(0).responseData();

        var json = com.agenticai.core.json.JsonMappers.plain().readTree(placeholder);

        assertTrue(json.isObject(), placeholder);
        assertTrue(json.path("context_cleared").asBoolean(), placeholder);
        assertEquals("web_search", json.path("tool").asText());
        assertEquals(10_000, json.path("chars").asInt());
    }
}
