package com.agenticai.core.graphmemory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

/**
 * Same mocked-{@link ChatClient} approach as the guardrails/agents modules' verifier tests (see
 * {@code ChatClientToolCallVerifierServiceTest}'s javadoc for the full reasoning) - covers {@link
 * EntityExtractionService}'s parsing contract: a clean JSON array parses, a markdown-fenced
 * response still parses, an empty array is a legitimate "no relationship" answer, and any
 * failure (blank content, malformed JSON, thrown exception) fails open to an empty list rather
 * than propagating.
 */
class EntityExtractionServiceTest {

    private final EntityExtractionService service = new EntityExtractionService();

    @Test
    void parsesASingleTriple() {
        ChatClient chatClient = mockChatClientReturning(
            "[{\"subject\": \"Priya\", \"relation\": \"MANAGER_OF_BY\", \"object\": \"Raj\"}]"
        );

        List<GraphTriple> triples = service.extractTriples(chatClient, "Priya's manager is Raj");

        assertThat(triples).containsExactly(new GraphTriple("Priya", "MANAGER_OF_BY", "Raj"));
    }

    @Test
    void parsesAMarkdownFencedResponse() {
        ChatClient chatClient = mockChatClientReturning(
            "```json\n[{\"subject\": \"Acme\", \"relation\": \"LOCATED_IN\", \"object\": \"Berlin\"}]\n```"
        );

        List<GraphTriple> triples = service.extractTriples(chatClient, "Acme is based in Berlin");

        assertThat(triples).containsExactly(new GraphTriple("Acme", "LOCATED_IN", "Berlin"));
    }

    @Test
    void parsesAnArrayWrappedInProse() {
        ChatClient chatClient = mockChatClientReturning(
            "Here are the triples:\n[{\"subject\": \"Acme\", \"relation\": \"LOCATED_IN\", \"object\": \"Berlin\"}]\nDone."
        );

        List<GraphTriple> triples = service.extractTriples(chatClient, "Acme is based in Berlin");

        assertThat(triples).containsExactly(new GraphTriple("Acme", "LOCATED_IN", "Berlin"));
    }

    @Test
    void returnsEmptyListForAVagueFactWithNoRelationship() {
        ChatClient chatClient = mockChatClientReturning("[]");

        List<GraphTriple> triples = service.extractTriples(chatClient, "I prefer email over WhatsApp");

        assertThat(triples).isEmpty();
    }

    @Test
    void returnsEmptyListOnMalformedJson() {
        ChatClient chatClient = mockChatClientReturning("not valid json at all");

        List<GraphTriple> triples = service.extractTriples(chatClient, "some fact");

        assertThat(triples).isEmpty();
    }

    @Test
    void skipsTriplesMissingAField() {
        ChatClient chatClient = mockChatClientReturning(
            "[{\"subject\": \"Priya\", \"object\": \"Raj\"}, "
                + "{\"subject\": \"Acme\", \"relation\": \"LOCATED_IN\", \"object\": \"Berlin\"}]"
        );

        List<GraphTriple> triples = service.extractTriples(chatClient, "two facts, one incomplete");

        assertThat(triples).containsExactly(new GraphTriple("Acme", "LOCATED_IN", "Berlin"));
    }

    @Test
    void failsOpenWhenChatClientIsNull() {
        assertThat(service.extractTriples(null, "some fact")).isEmpty();
    }

    @Test
    void failsOpenWhenFactTextIsBlank() {
        ChatClient chatClient = mock(ChatClient.class);

        assertThat(service.extractTriples(chatClient, "   ")).isEmpty();
    }

    @Test
    void failsOpenWhenChatClientThrows() {
        ChatClient chatClient = mock(ChatClient.class);
        when(chatClient.prompt()).thenThrow(new RuntimeException("provider outage"));

        assertThat(service.extractTriples(chatClient, "some fact")).isEmpty();
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
