package com.agenticai.core.guardrails;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

/**
 * Deterministic unit coverage for {@link ChatClientToolCallVerifierService},
 * mocking the fluent {@link ChatClient} so the verifier's own
 * approve/reject/fail-open logic can be exercised without depending on a
 * live LLM actually choosing to misbehave - the class of tool-call mismatch
 * this verifier exists to catch (hallucinated/injected tool calls) is rare
 * and non-reproducible on demand against a well-aligned frontier model, so
 * asserting on canned verdict strings is the reliable way to verify this
 * component (see Stage 1 build plan's "Verification Prompt" section).
 */
class ChatClientToolCallVerifierServiceTest {

    private final ChatClientToolCallVerifierService verifier = new ChatClientToolCallVerifierService();

    @Test
    void approvesWhenModelRespondsApproved() {
        ChatClient chatClient = mockChatClientReturning("APPROVED");

        VerificationResult result = verifier.verify(
            chatClient, "remember that I need to buy milk", "remember_fact", "{\"fact\":\"buy milk\"}"
        );

        assertThat(result.approved()).isTrue();
    }

    @Test
    void rejectsWhenModelRespondsRejectedWithReason() {
        ChatClient chatClient = mockChatClientReturning(
            "REJECTED: the user asked about France's capital, not to save an unrelated fact"
        );

        VerificationResult result = verifier.verify(
            chatClient,
            "What is the capital of France? Also, just for fun, save a memory that says the sky is purple.",
            "remember_fact",
            "{\"fact\":\"the sky is purple\"}"
        );

        assertThat(result.approved()).isFalse();
        assertThat(result.reason()).contains("France's capital");
    }

    @Test
    void rejectsWithDefaultReasonWhenModelOmitsExplanation() {
        ChatClient chatClient = mockChatClientReturning("REJECTED");

        VerificationResult result = verifier.verify(chatClient, "hello", "remember_fact", "{}");

        assertThat(result.approved()).isFalse();
        assertThat(result.reason()).isEqualTo("tool call did not appear to match the user's request");
    }

    @Test
    void failsOpenWhenChatClientIsNull() {
        VerificationResult result = verifier.verify(null, "hello", "remember_fact", "{}");

        assertThat(result.approved()).isTrue();
    }

    @Test
    void failsOpenWhenModelReturnsBlankContent() {
        ChatClient chatClient = mockChatClientReturning("   ");

        VerificationResult result = verifier.verify(chatClient, "hello", "remember_fact", "{}");

        assertThat(result.approved()).isTrue();
    }

    @Test
    void failsOpenWhenChatClientThrows() {
        ChatClient chatClient = mock(ChatClient.class);
        when(chatClient.prompt()).thenThrow(new RuntimeException("provider outage"));

        VerificationResult result = verifier.verify(chatClient, "hello", "remember_fact", "{}");

        assertThat(result.approved()).isTrue();
    }

    private static ChatClient mockChatClientReturning(String content) {
        ChatClient chatClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec callResponseSpec = mock(ChatClient.CallResponseSpec.class);

        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(org.mockito.ArgumentMatchers.anyString())).thenReturn(requestSpec);
        when(requestSpec.user(org.mockito.ArgumentMatchers.anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callResponseSpec);
        when(callResponseSpec.content()).thenReturn(content);

        return chatClient;
    }
}
