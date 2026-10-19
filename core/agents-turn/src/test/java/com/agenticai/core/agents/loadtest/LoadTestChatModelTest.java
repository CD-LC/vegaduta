package com.agenticai.core.agents.loadtest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

/**
 * Covers {@link LoadTestChatModel}'s contract: deterministic (no real model,
 * no network), visibly-synthetic output, and a genuinely CHUNKED streaming
 * path (not one chunk masquerading as streaming) so it exercises the same
 * SSE machinery a real provider stream would.
 */
class LoadTestChatModelTest {

    private final LoadTestChatModel model = new LoadTestChatModel();

    @Test
    void callNeverThrowsAndReturnsACannedAssistantMessage() {
        ChatResponse response = model.call(new Prompt("hello there"));

        String content = response.getResult().getOutput().getText();
        assertTrue(content.contains("LOAD-TEST MOCK"), "response should be clearly marked as synthetic: " + content);
        assertTrue(content.contains("echo-length=11"), "should echo the prompt's character length: " + content);
    }

    @Test
    void differentPromptLengthsProduceDifferentEchoedLengths() {
        ChatResponse shortResponse = model.call(new Prompt("hi"));
        ChatResponse longResponse = model.call(new Prompt("a much longer message than the other one"));

        String shortContent = shortResponse.getResult().getOutput().getText();
        String longContent = longResponse.getResult().getOutput().getText();
        assertTrue(shortContent.contains("echo-length=2"));
        assertFalse(shortContent.equals(longContent));
    }

    @Test
    void aNullOrEmptyPromptContentsNeverThrows() {
        // Prompt("") still has non-null getContents() in Spring AI, but this
        // guards the defensive null-check in cannedResponse() regardless.
        ChatResponse response = model.call(new Prompt(""));
        assertTrue(response.getResult().getOutput().getText().contains("echo-length=0"));
    }

    @Test
    void streamEmitsSeveralSmallChunksInsteadOfOneWholeResponse() {
        Flux<ChatResponse> stream = model.stream(new Prompt("stream this please"));

        List<ChatResponse> chunks = stream.collectList().block();
        assertTrue(chunks.size() >= 4 && chunks.size() <= 8,
            "should emit 4-8 small chunks per the load-test seam's spec, got " + chunks.size());

        // Concatenating every chunk's text must reconstitute the exact same
        // canned response call() would have returned for an identical prompt -
        // streaming must not change WHAT is said, only how it's delivered.
        StringBuilder reassembled = new StringBuilder();
        for (ChatResponse chunk : chunks) {
            reassembled.append(chunk.getResult().getOutput().getText());
        }
        String directCallContent = model.call(new Prompt("stream this please")).getResult().getOutput().getText();
        // The two calls happen at (near-)identical millis in the same test
        // method, but the canned template embeds System.currentTimeMillis(),
        // so compare everything EXCEPT the timestamp segment rather than the
        // full string.
        assertEquals(
            directCallContent.replaceAll("ts=\\d+", "ts=X"),
            reassembled.toString().replaceAll("ts=\\d+", "ts=X")
        );
    }

    @Test
    void streamNeverEmitsAnEmptyChunk() {
        List<ChatResponse> chunks = model.stream(new Prompt("x")).collectList().block();
        for (ChatResponse chunk : chunks) {
            assertFalse(chunk.getResult().getOutput().getText().isEmpty());
        }
    }
}
