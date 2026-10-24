package com.agenticai.core.agents.e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.chat.ResilientToolCallingManagerFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingManager;

/** The scripted model records what it was sent, drives the tool loop and reports finish reasons. */
class ScriptedLlmTest {

    private final ToolCallingManager manager =
            new ResilientToolCallingManagerFactory(new ObjectMapper()).create();

    @Test
    void aToolCallingReplyRunsTheToolAndTheNextRoundTripIsSentItsResult() {
        RecordingTool lookup = RecordingTool.returning("lookup", "Looks things up", "RESULT-123");
        ScriptedLlm llm =
                ScriptedLlm.sequence(
                        manager,
                        ScriptedLlm.callTool("c1", "lookup", "{\"to\":\"x\"}"),
                        ScriptedLlm.text("The answer is RESULT-123."));
        ChatClient client = ApprovalTurnHarness.chatClientOver(llm);

        String answer = client.prompt().user("find it").toolCallbacks(lookup).call().content();

        assertEquals("The answer is RESULT-123.", answer);
        assertEquals(1, lookup.executions());
        List<ScriptedLlm.Request> requests = llm.requests();
        assertEquals(2, requests.size(), "one entry per provider round trip");
        assertEquals(List.of("lookup"), requests.get(0).offeredTools());
        assertTrue(requests.get(0).userTexts().contains("find it"));
        assertTrue(requests.get(0).toolResults().isEmpty());
        assertTrue(requests.get(1).hasToolResult());
        assertTrue(requests.get(1).toolResults().get(0).contains("RESULT-123"));
        assertTrue(
                requests.get(1).userTexts().contains("find it"),
                "the goal is still in the second round trip");
    }

    @Test
    void streamingDeliversTheTextAndRunsTheSameToolLoop() {
        RecordingTool lookup = RecordingTool.returning("lookup", "Looks things up", "RESULT-9");
        ScriptedLlm llm =
                ScriptedLlm.sequence(
                        manager,
                        ScriptedLlm.callTool("c1", "lookup", "{}"),
                        ScriptedLlm.text("Streamed RESULT-9 reply."));
        ChatClient client = ApprovalTurnHarness.chatClientOver(llm);

        String answer =
                String.join(
                        "",
                        client.prompt().user("go").toolCallbacks(lookup).stream()
                                .content()
                                .collectList()
                                .block());

        assertEquals("Streamed RESULT-9 reply.", answer);
        assertEquals(1, lookup.executions());
        assertEquals(2, llm.modelCalls());
    }

    @Test
    void truncationAndSafetyStopsReachTheResponseAsProviderFinishReasons() {
        ScriptedLlm llm =
                ScriptedLlm.sequence(
                        manager,
                        ScriptedLlm.truncated("The invoice was sent to bil"),
                        ScriptedLlm.safetyStopped(""),
                        ScriptedLlm.text("fine"));

        ChatResponse truncated = llm.call(new Prompt("one"));
        ChatResponse blocked = llm.call(new Prompt("two"));
        ChatResponse normal = llm.call(new Prompt("three"));

        assertEquals("length", truncated.getResult().getMetadata().getFinishReason());
        assertEquals("The invoice was sent to bil", truncated.getResult().getOutput().getText());
        assertEquals("content_filter", blocked.getResult().getMetadata().getFinishReason());
        assertEquals("stop", normal.getResult().getMetadata().getFinishReason());
    }

    @Test
    void aResponderSeesTheRequestSoTheModelCanActOnWhatItWasShown() {
        ScriptedLlm llm =
                new ScriptedLlm(
                        request ->
                                request.mentions("SECRET-7")
                                        ? ScriptedLlm.text("saw it")
                                        : ScriptedLlm.text("saw nothing"),
                        manager);

        assertEquals(
                "saw nothing", llm.call(new Prompt("hello")).getResult().getOutput().getText());
        assertEquals(
                "saw it", llm.call(new Prompt("hello SECRET-7")).getResult().getOutput().getText());
    }

    @Test
    void aRunawayLoopPastTheScriptFailsLoudly() {
        ScriptedLlm llm = ScriptedLlm.sequence(manager, ScriptedLlm.text("only one"));
        llm.call(new Prompt("a"));

        IllegalStateException exhausted =
                assertThrows(IllegalStateException.class, () -> llm.call(new Prompt("b")));

        assertTrue(exhausted.getMessage().contains("exhausted"));
    }
}
