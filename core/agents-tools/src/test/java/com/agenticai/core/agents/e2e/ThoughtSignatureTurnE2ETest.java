package com.agenticai.core.agents.e2e;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.agenticai.core.agents.core.ReasoningDepth;
import com.agenticai.core.agents.turn.cache.LlmResponseCacheService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.retry.NonTransientAiException;

/**
 * Gemini refuses the request that follows a tool call when the model's own call goes back without its
 * thought signature. The platform sends it back intact now, so this should not happen; when the provider
 * still refuses, the turn used to be silently re-run with every tool removed and the answer cached as an
 * ordinary one. Run through the real turn pipeline; only the provider is scripted.
 *
 * <p>What these pin: the degraded answer is announced to the person in the reply itself, the model is shown
 * what the tools already returned (it used to be shown nothing and could claim anything), the tool is not
 * run a second time, and the reply is not cached.
 */
class ThoughtSignatureTurnE2ETest {

    private static final String GOAL = "Where is order 1042?";
    private static final String REFUSAL =
            "400 - {\"error\":{\"message\":\"Function call is missing a thought_signature in functionCall parts."
                    + " This is required for tools to work correctly.\"}}";

    private final LlmResponseCacheService cache = mock(LlmResponseCacheService.class);
    private ApprovalTurnHarness harness;

    @AfterEach
    void close() {
        if (harness != null) {
            harness.close();
        }
    }

    private static RecordingTool getOrder() {
        return RecordingTool.returning("get_order", "Looks up an order", "{\"order\":1042,\"status\":\"SHIPPED\"}");
    }

    /** Calls the tool first; refuses the request that carries its result; answers a request that has no tools. */
    private static ScriptedLlm.Responder refusesAfterTheToolResult() {
        return request -> {
            if (request.offeredTools().isEmpty()) {
                return ScriptedLlm.text("From the lookup: order 1042 is SHIPPED.");
            }
            if (request.hasToolResult()) {
                throw new NonTransientAiException(REFUSAL);
            }
            return ScriptedLlm.callTool("c1", "get_order", "{\"to\":\"1042\"}");
        };
    }

    private ApprovalTurnHarness.Builder base(ScriptedLlm.Responder responder) {
        // The degraded retry is opt-in (app.chat.resilience.thought-signature-degrade defaults to false).
        return ApprovalTurnHarness.builder()
                .tool(getOrder())
                .responder(responder)
                .responseCache(cache)
                .realErrorTranslation()
                .resilience(p -> p.setThoughtSignatureDegrade(true));
    }

    @Test
    void aFastTurnIsAnsweredWithoutToolsVisiblyAndFromWhatTheToolReturned() {
        harness = base(refusesAfterTheToolResult()).build();

        String reply = harness.chat(GOAL);

        ScriptedLlm.Request degraded = harness.llm().lastRequest();
        assertAll(
                () -> assertTrue(reply.contains("written without any tools"), "the person is told in the reply: " + reply),
                () -> assertTrue(reply.contains("get_order"), "and which steps had already run: " + reply),
                () -> assertTrue(reply.endsWith("From the lookup: order 1042 is SHIPPED."), reply),
                () -> assertTrue(degraded.offeredTools().isEmpty(), "no tools in the degraded pass"),
                () -> assertTrue(degraded.mentions("SHIPPED"), "the model is shown what the tool returned: " + degraded.conversation()),
                () -> assertEquals(1, harness.tool("get_order").executions(), "the tool is not run again"),
                () -> verify(cache, never()).put(any(), any(), any(), any(), any(), any()));
    }

    @Test
    void aBufferedTurnIsAnsweredTheSameWay() {
        harness = base(refusesAfterTheToolResult()).depth(ReasoningDepth.CAREFUL).build();

        String reply = harness.chat(GOAL);

        ScriptedLlm.Request degraded = harness.llm().lastRequest();
        assertAll(
                () -> assertTrue(reply.contains("written without any tools"), reply),
                () -> assertTrue(reply.endsWith("From the lookup: order 1042 is SHIPPED."), reply),
                () -> assertTrue(degraded.offeredTools().isEmpty()),
                () -> assertTrue(degraded.mentions("SHIPPED"), degraded.conversation()),
                () -> assertEquals(1, harness.tool("get_order").executions()),
                () -> verify(cache, never()).put(any(), any(), any(), any(), any(), any()));
    }

    @Test
    void aTurnThatRanNoToolIsAlsoToldThatItHadNone() {
        harness =
                base(request -> {
                            if (request.offeredTools().isEmpty()) {
                                return ScriptedLlm.text("I cannot look that up here.");
                            }
                            throw new NonTransientAiException(REFUSAL);
                        })
                        .build();

        String reply = harness.chat(GOAL);

        assertAll(
                () -> assertTrue(reply.contains("written without any tools"), reply),
                () -> assertFalse(harness.llm().lastRequest().mentions("Your tools are no longer available"),
                        "no tool ran, so there is no tool result to show: " + harness.llm().lastRequest().conversation()));
    }

    @Test
    void byDefaultTheProvidersRefusalIsReportedAndNoToolsFreeAnswerIsPassedOff() {
        harness =
                ApprovalTurnHarness.builder()
                        .tool(getOrder())
                        .responder(refusesAfterTheToolResult())
                        .responseCache(cache)
                        .realErrorTranslation()
                        .build();

        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class, () -> harness.chat(GOAL));

        assertAll(
                () -> assertEquals(1, harness.tool("get_order").executions(), "the tool is not run again"),
                () -> assertTrue(harness.llm().lastRequest().hasToolResult(), "no tool-free pass was made"),
                () -> verify(cache, never()).put(any(), any(), any(), any(), any(), any()));
    }
}
