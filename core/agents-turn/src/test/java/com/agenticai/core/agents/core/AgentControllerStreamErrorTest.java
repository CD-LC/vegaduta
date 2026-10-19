package com.agenticai.core.agents.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.agents.turn.AgentChatStreamResponder;
import com.agenticai.core.chat.UpstreamProviderException;
import com.agenticai.core.guardrails.GuardrailViolationException;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;

/**
 * The stream-error sentinel and the human half that rides on it.
 *
 * <p>2026-07-29: the platform owner saw the literal text {@code @@AGENT_CHAT_STREAM_ERROR_...} in
 * the chat window when asking an agent for an image. The sentinel itself was working exactly as
 * designed - what was missing was a deployed frontend that strips it (see {@code
 * web/app/lib/api.ts}'s mirrored constant). Two things are worth pinning down here so the contract
 * cannot drift on the backend side: the literal is exactly what the client mirrors, and the text
 * that follows it is the most specific thing we actually know, not always a generic apology.
 */
class AgentControllerStreamErrorTest {

    /**
     * Byte-for-byte identical to {@code STREAM_ERROR_MARKER} in {@code web/app/lib/api.ts}. Written
     * out as a literal on purpose rather than referencing the constant - the whole point is to fail
     * loudly if someone edits one side of a two-codebase contract.
     */
    private static final String CLIENT_MIRRORED_MARKER = "@@AGENT_CHAT_STREAM_ERROR_9f2b1c@@";

    @Test
    void markerMatchesTheLiteralTheFrontendLooksFor() {
        assertEquals(CLIENT_MIRRORED_MARKER, AgentController.STREAM_ERROR_MARKER);
    }

    @Test
    void markerIsPrefixedSoTheClientCanDetectItAtTheStartOfAnSseDataLine() {
        String chunk =
                AgentController.STREAM_ERROR_MARKER
                        + AgentChatStreamResponder.midStreamFailureMessage(
                                new RuntimeException("boom"));
        assertTrue(chunk.startsWith(CLIENT_MIRRORED_MARKER));
        // What the client renders after stripping must be non-empty, or the
        // user gets a blank error bar.
        assertFalse(chunk.substring(CLIENT_MIRRORED_MARKER.length()).isBlank());
    }

    @Test
    void anUpstreamFailureKeepsItsActionableConsumerMessage() {
        UpstreamProviderException upstream =
                new UpstreamProviderException(
                        429,
                        "The LLM provider rejected the request (HTTP 429): rate_limit_exceeded",
                        "This model is rate limited right now. Try again in a minute.");

        assertEquals(
                "This model is rate limited right now. Try again in a minute.",
                AgentChatStreamResponder.midStreamFailureMessage(upstream));
    }

    @Test
    void aWrappedUpstreamFailureIsStillUnwrapped() {
        UpstreamProviderException upstream =
                new UpstreamProviderException(402, "technical", "Add credit to keep chatting.");

        assertEquals(
                "Add credit to keep chatting.",
                AgentChatStreamResponder.midStreamFailureMessage(
                        new CompletionException(new IllegalStateException(upstream))));
    }

    /**
     * 2026-08-05: live staging report - a Vertex AI 403 turned into an error message that itself
     * falsely tripped the semantic guardrail's prompt-injection check ({@code
     * GuardrailViolationException} thrown from {@code SemanticGuardrailService}). Before this fix,
     * a guardrail violation landing mid-stream (after the first token) fell through to the generic
     * "something went wrong" sentence below - both the real upstream failure AND the fact a
     * guardrail fired were hidden from the user. {@code
     * GlobalExceptionHandler#handleGuardrailViolation} already treats this exception's own message
     * as consumer-safe on the pre-first-token path (422); this pins the same treatment mid-stream.
     */
    @Test
    void aGuardrailViolationKeepsItsOwnConsumerMessage() {
        GuardrailViolationException guardrail =
                new GuardrailViolationException(
                        "generate_image",
                        "error message contains direct instructions to the AI on how to respond");

        assertEquals(
                "Guardrail blocked output from tool 'generate_image': "
                        + "error message contains direct instructions to the AI on how to respond",
                AgentChatStreamResponder.midStreamFailureMessage(guardrail));
    }

    @Test
    void aWrappedGuardrailViolationIsStillUnwrapped() {
        GuardrailViolationException guardrail =
                new GuardrailViolationException("speak", "flagged content");

        assertEquals(
                "Guardrail blocked output from tool 'speak': flagged content",
                AgentChatStreamResponder.midStreamFailureMessage(
                        new CompletionException(new IllegalStateException(guardrail))));
    }

    @Test
    void anArbitraryFailureNeverLeaksItsTechnicalMessage() {
        String message =
                AgentChatStreamResponder.midStreamFailureMessage(
                        new IllegalStateException(
                                "No ToolCallback found for tool name: generate_image"));

        assertFalse(message.contains("ToolCallback"), message);
        assertEquals("Something went wrong finishing this reply. Please try again.", message);
    }

    @Test
    void aSelfReferencingCauseChainDoesNotHang() {
        RuntimeException looping =
                new RuntimeException("loop") {
                    @Override
                    public synchronized Throwable getCause() {
                        return this;
                    }
                };

        assertEquals(
                "Something went wrong finishing this reply. Please try again.",
                AgentChatStreamResponder.midStreamFailureMessage(looping));
    }
}
