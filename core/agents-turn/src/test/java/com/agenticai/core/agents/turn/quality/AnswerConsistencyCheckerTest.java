package com.agenticai.core.agents.turn.quality;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

/**
 * Mirrors {@code ChatClientToolCallVerifierServiceTest}'s mocking approach (mock the fluent
 * {@link ChatClient} rather than depend on a live model producing a specific verdict on demand -
 * see that test's javadoc for the full reasoning). Covers {@link AnswerConsistencyChecker}'s
 * Definition of Done: a flagged answer costs exactly one revision call, a clean answer costs
 * zero, and any failure at any step falls back to the original answer.
 */
class AnswerConsistencyCheckerTest {

    private final AnswerConsistencyChecker checker = new AnswerConsistencyChecker();

    @Test
    void returnsOriginalAnswerWhenConsistent() {
        ChatClient chatClient = mockSequentialResponses("CONSISTENT");

        String result = checker.checkAndRevise(chatClient, "What is the capital of France?", "Paris.");

        assertThat(result).isEqualTo("Paris.");
    }

    @Test
    void revisesOnceWhenFlagged() {
        ChatClient chatClient = mockSequentialResponses(
            "REVISE: contradicts itself about the capital city",
            "Paris is the capital of France."
        );

        String result = checker.checkAndRevise(chatClient, "What is the capital of France?", "Lyon is, no wait, Paris.");

        assertThat(result).isEqualTo("Paris is the capital of France.");
    }

    @Test
    void fallsBackToOriginalWhenRevisionCallReturnsBlank() {
        ChatClient chatClient = mockSequentialResponses("REVISE: unclear", "   ");

        String result = checker.checkAndRevise(chatClient, "hello", "original answer");

        assertThat(result).isEqualTo("original answer");
    }

    @Test
    void failsOpenWhenChatClientIsNull() {
        String result = checker.checkAndRevise(null, "hello", "original answer");

        assertThat(result).isEqualTo("original answer");
    }

    @Test
    void failsOpenWhenChatClientThrows() {
        ChatClient chatClient = mock(ChatClient.class);
        when(chatClient.prompt()).thenThrow(new RuntimeException("provider outage"));

        String result = checker.checkAndRevise(chatClient, "hello", "original answer");

        assertThat(result).isEqualTo("original answer");
    }

    @Test
    void neverCallsRevisionWhenAnswerIsConsistent() {
        ChatClient chatClient = mockSequentialResponses("CONSISTENT");

        checker.checkAndRevise(chatClient, "hello", "a fine answer");

        // Exactly one prompt() call (the check) - the revision prompt never fires.
        verify(chatClient, times(1)).prompt();
    }

    @Test
    void includesToolEvidenceAndHistoryInCheckPrompt() {
        ChatClient chatClient = mockSequentialResponses("CONSISTENT");

        String result = checker.checkAndRevise(
            chatClient, "What's the weather?", "It's sunny.",
            "get_weather -> {\"conditions\":\"rain\"}", "USER: what about yesterday?"
        );

        assertThat(result).isEqualTo("It's sunny.");
        ChatClient.ChatClientRequestSpec requestSpec = chatClient.prompt();
        verify(requestSpec).user(org.mockito.ArgumentMatchers.contains("get_weather -> {\"conditions\":\"rain\"}"));
        verify(requestSpec).user(org.mockito.ArgumentMatchers.contains("USER: what about yesterday?"));
    }

    @Test
    void omitsEvidenceAndHistoryBlocksWhenNotSupplied() {
        ChatClient chatClient = mockSequentialResponses("CONSISTENT");

        checker.checkAndRevise(chatClient, "hello", "original answer", null, null);

        ChatClient.ChatClientRequestSpec requestSpec = chatClient.prompt();
        verify(requestSpec).user("User's question: hello\n\nAnswer already given: original answer");
    }

    /** Returns a mocked {@link ChatClient} whose successive {@code .call().content()} results are queued in order. */
    private static ChatClient mockSequentialResponses(String... contents) {
        ChatClient chatClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec callResponseSpec = mock(ChatClient.CallResponseSpec.class);

        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(org.mockito.ArgumentMatchers.anyString())).thenReturn(requestSpec);
        when(requestSpec.user(org.mockito.ArgumentMatchers.anyString())).thenReturn(requestSpec);
        // Cost attribution (2026-09-17): these prompts now carry
        // .advisors(LlmMetricsAdvisor.attributeTo(agentId, sessionId)) so the extra
        // completions a non-FAST reasoning depth pays for stop landing in llm_call_log with a
        // null agent. An unstubbed fluent step returns null, which the service's own
        // catch-all would have silently turned into "fall back to the original answer" -
        // i.e. these tests would still pass while asserting nothing.
        when(requestSpec.advisors(
            org.mockito.ArgumentMatchers.<java.util.function.Consumer<ChatClient.AdvisorSpec>>any()))
            .thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callResponseSpec);

        if (contents.length == 1) {
            when(callResponseSpec.content()).thenReturn(contents[0]);
        } else {
            when(callResponseSpec.content()).thenReturn(contents[0], java.util.Arrays.copyOfRange(contents, 1, contents.length));
        }
        return chatClient;
    }
}
