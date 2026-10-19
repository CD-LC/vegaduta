package com.agenticai.core.agents.turn.quality;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

/** Same mocked-{@link ChatClient} approach as {@code AnswerConsistencyCheckerTest}. */
class ReflexionServiceTest {

    private final ReflexionService reflexion = new ReflexionService();

    @Test
    void returnsCandidateUnchangedWhenSolved() {
        ChatClient chatClient = mockSequentialResponses("SOLVED");

        String result = reflexion.critiqueAndMaybeRevise(chatClient, "book me a flight to Paris", "Done - booked.");

        assertThat(result).isEqualTo("Done - booked.");
    }

    @Test
    void revisesOnceWhenIncomplete() {
        ChatClient chatClient = mockSequentialResponses(
            "INCOMPLETE: never mentioned the return flight the user also asked for",
            "Booked your outbound and return flights to Paris."
        );

        String result = reflexion.critiqueAndMaybeRevise(
            chatClient, "book me a round trip to Paris", "Booked your outbound flight to Paris."
        );

        assertThat(result).isEqualTo("Booked your outbound and return flights to Paris.");
    }

    @Test
    void fallsBackToCandidateWhenRevisionReturnsBlank() {
        ChatClient chatClient = mockSequentialResponses("INCOMPLETE: missing detail", "");

        String result = reflexion.critiqueAndMaybeRevise(chatClient, "hello", "candidate answer");

        assertThat(result).isEqualTo("candidate answer");
    }

    @Test
    void failsOpenWhenChatClientIsNull() {
        String result = reflexion.critiqueAndMaybeRevise(null, "hello", "candidate answer");

        assertThat(result).isEqualTo("candidate answer");
    }

    @Test
    void failsOpenWhenChatClientThrows() {
        ChatClient chatClient = mock(ChatClient.class);
        when(chatClient.prompt()).thenThrow(new RuntimeException("provider outage"));

        String result = reflexion.critiqueAndMaybeRevise(chatClient, "hello", "candidate answer");

        assertThat(result).isEqualTo("candidate answer");
    }

    @Test
    void neverCallsRevisionWhenSolved() {
        ChatClient chatClient = mockSequentialResponses("SOLVED");

        reflexion.critiqueAndMaybeRevise(chatClient, "hello", "a complete answer");

        verify(chatClient, times(1)).prompt();
    }

    @Test
    void includesToolEvidenceAndHistoryInCritiquePrompt() {
        ChatClient chatClient = mockSequentialResponses("SOLVED");

        String result = reflexion.critiqueAndMaybeRevise(
            chatClient, "book me a flight to Paris", "Done - booked.",
            "book_flight -> {\"status\":\"pending\"}", "ASSISTANT: I'll need your travel dates first."
        );

        assertThat(result).isEqualTo("Done - booked.");
        ChatClient.ChatClientRequestSpec requestSpec = chatClient.prompt();
        verify(requestSpec).user(org.mockito.ArgumentMatchers.contains("book_flight -> {\"status\":\"pending\"}"));
        verify(requestSpec).user(org.mockito.ArgumentMatchers.contains("ASSISTANT: I'll need your travel dates first."));
    }

    @Test
    void omitsEvidenceAndHistoryBlocksWhenNotSupplied() {
        ChatClient chatClient = mockSequentialResponses("SOLVED");

        reflexion.critiqueAndMaybeRevise(chatClient, "hello", "candidate answer", null, null);

        ChatClient.ChatClientRequestSpec requestSpec = chatClient.prompt();
        verify(requestSpec).user("User's request: hello\n\nAnswer given: candidate answer");
    }

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
