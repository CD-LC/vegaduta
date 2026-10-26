package com.agenticai.core.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

/**
 * {@link TerminalRoundStreamAdvisor}: a tool-using stream's narration rounds are held and dropped;
 * the round that ends without tool calls is emitted whole. A never-finished round flushes at stream
 * end so nothing is swallowed.
 */
class TerminalRoundStreamAdvisorTest {

    private final TerminalRoundStreamAdvisor advisor = new TerminalRoundStreamAdvisor();

    private static ChatClientResponse chunk(String text) {
        return chunk(text, null);
    }

    private static ChatClientResponse chunk(String text, String finishReason) {
        Generation generation =
                finishReason == null
                        ? new Generation(new AssistantMessage(text))
                        : new Generation(
                                new AssistantMessage(text),
                                ChatGenerationMetadata.builder().finishReason(finishReason).build());
        return ChatClientResponse.builder()
                .chatResponse(new ChatResponse(List.of(generation)))
                .context(Map.of())
                .build();
    }

    private List<String> emit(Flux<ChatClientResponse> upstream) {
        StreamAdvisorChain chain = mock(StreamAdvisorChain.class);
        org.mockito.Mockito.when(chain.nextStream(org.mockito.ArgumentMatchers.any()))
                .thenReturn(upstream);
        return advisor
                .adviseStream(mock(ChatClientRequest.class), chain)
                .map(r -> r.chatResponse().getResult().getOutput().getText())
                .collectList()
                .block();
    }

    @Test
    void aStreamWithNoToolRoundsPassesThroughWhole() {
        List<String> out = emit(Flux.just(chunk("Hello "), chunk("world.", "stop")));
        assertEquals(List.of("Hello ", "world."), out);
    }

    @Test
    void aToolCallRoundIsDroppedAndTheTerminalRoundEmitted() {
        List<String> out =
                emit(
                        Flux.just(
                                chunk("Let me search for that.", "tool_calls"),
                                chunk("The price is "),
                                chunk("80166.", "stop")));
        assertEquals(List.of("The price is ", "80166."), out);
    }

    @Test
    void severalNarrationRoundsAllDrop() {
        List<String> out =
                emit(
                        Flux.just(
                                chunk("thinking about it", "tool_calls"),
                                chunk("still working", "tool_use"),
                                chunk("the answer", "stop")));
        assertEquals(List.of("the answer"), out);
    }

    @Test
    void aStreamEndingMidRoundFlushesWhatWasBuffered() {
        List<String> out = emit(Flux.just(chunk("partial answer with no reason")));
        assertEquals(List.of("partial answer with no reason"), out);
    }

    @Test
    void aToolOnlyRoundEmitsNothing() {
        List<String> out =
                emit(Flux.just(chunk("", "tool_calls"), chunk("done.", "stop")));
        assertEquals(List.of("done."), out);
    }

    @Test
    void theMalformedToolCallReasonAlsoMarksARoundNonTerminal() {
        List<String> out =
                emit(
                        Flux.just(
                                chunk("misfire", "unexpected_tool_call"),
                                chunk("recovered.", "stop")));
        assertEquals(List.of("recovered."), out);
    }

    @Test
    void nothingUpstreamMeansNothingEmitted() {
        List<String> out = emit(Flux.empty());
        assertTrue(out == null || out.isEmpty());
    }
}
