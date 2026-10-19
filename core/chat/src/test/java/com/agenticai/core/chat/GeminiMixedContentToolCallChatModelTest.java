package com.agenticai.core.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.genai.Client;
import com.google.genai.types.Candidate;
import com.google.genai.types.Content;
import com.google.genai.types.FinishReason;
import com.google.genai.types.Part;
import io.micrometer.observation.ObservationRegistry;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;

/**
 * Locks in the fix for spring-projects/spring-ai#5466: reported 2026-08-09
 * from the agent playground - a Gemini agent with MCP tools auto-selected
 * (puppeteer) replied "I cannot access external URLs" with 0 tool calls
 * logged for a plain "summarize this webpage &lt;url&gt;" message. Traced to
 * upstream {@code GoogleGenAiChatModel#responseCandidateToGeneration} using
 * {@code allMatch} to decide a candidate is a tool call, so a Gemini
 * candidate that mixes a lead-in text/thought part with its functionCall
 * part fails that check and the functionCall is silently dropped - no
 * exception, no tool_call_log row, just a text-only response. See
 * {@link GeminiMixedContentToolCallChatModel}'s javadoc for the full
 * mechanism and the upstream jar bytecode citation.
 *
 * <p>{@code responseCandidateToGeneration} is only touched via its instance
 * (protected, same-package) so no mock server call ever happens - the
 * {@link Client}/{@link GoogleGenAiChatOptions}/{@link ToolCallingManager}
 * passed to the constructor exist solely to satisfy its {@code Assert
 * .notNull} checks.
 */
class GeminiMixedContentToolCallChatModelTest {

    private static final GeminiMixedContentToolCallChatModel MODEL = new GeminiMixedContentToolCallChatModel(
        Client.builder().apiKey("test-key").build(),
        GoogleGenAiChatOptions.builder().model("gemini-2.5-flash").build(),
        ToolCallingManager.builder().build(),
        ObservationRegistry.NOOP);

    @Test
    void mixedTextAndFunctionCallPartsStillProducesToolCall() {
        // The exact shape that trips the upstream allMatch bug: a lead-in
        // text part ("Let me check that page...") alongside the functionCall.
        Candidate candidate = Candidate.builder()
            .content(Content.fromParts(
                Part.fromText("Let me check that page for you."),
                Part.fromFunctionCall("browser_navigate", Map.of("url", "https://example.com/article"))))
            .finishReason(FinishReason.Known.STOP)
            .build();

        List<Generation> generations = MODEL.responseCandidateToGeneration(candidate);

        assertEquals(1, generations.size());
        AssistantMessage message = generations.get(0).getOutput();
        assertFalse(message.getToolCalls().isEmpty(), "functionCall part must survive as a tool call");
        assertEquals(1, message.getToolCalls().size());
        assertEquals("browser_navigate", message.getToolCalls().get(0).name());
        assertTrue(message.getToolCalls().get(0).arguments().contains("example.com/article"));
        assertEquals("Let me check that page for you.", message.getText());
    }

    @Test
    void pureFunctionCallCandidateStillWorks() {
        Candidate candidate = Candidate.builder()
            .content(Content.fromParts(Part.fromFunctionCall("browser_navigate", Map.of("url", "https://example.com"))))
            .finishReason(FinishReason.Known.STOP)
            .build();

        List<Generation> generations = MODEL.responseCandidateToGeneration(candidate);

        assertEquals(1, generations.size());
        AssistantMessage message = generations.get(0).getOutput();
        assertEquals(1, message.getToolCalls().size());
        assertEquals("browser_navigate", message.getToolCalls().get(0).name());
    }

    @Test
    void textOnlyCandidateIsUnaffected() {
        Candidate candidate = Candidate.builder()
            .content(Content.fromParts(Part.fromText("Just a normal answer, no tools needed.")))
            .finishReason(FinishReason.Known.STOP)
            .build();

        List<Generation> generations = MODEL.responseCandidateToGeneration(candidate);

        assertEquals(1, generations.size());
        AssistantMessage message = generations.get(0).getOutput();
        assertTrue(message.getToolCalls().isEmpty());
        assertEquals("Just a normal answer, no tools needed.", message.getText());
    }
}
