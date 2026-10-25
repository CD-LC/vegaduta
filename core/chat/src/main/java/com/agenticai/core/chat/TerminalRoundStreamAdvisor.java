package com.agenticai.core.chat;

import java.util.ArrayList;
import java.util.List;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.core.Ordered;
import reactor.core.publisher.Flux;

/**
 * Streams only the tool loop's terminal round. Between tool calls the model writes narration
 * ("let me search for that", "found it, now checking price") - assistant text that is not the answer,
 * and on a streamed turn every chunk reached the client, so users saw retries and mid-loop thinking
 * mixed into the reply. This advisor buffers one round's chunks at a time and releases them only
 * when the round ends WITHOUT tool calls; a round that ends in {@code tool_calls} drops its buffered
 * text entirely. The final round still arrives as one burst (its chunks are held until its finish
 * reason proves it terminal - live-per-token streaming of the answer is not possible, since a round
 * cannot be classified before it ends). A stream that ends on a never-finished round flushes what is
 * buffered, so nothing is ever swallowed.
 *
 * <p>Sits just outside {@link FinishReasonAdvisor} (the capture advisor is closer to the model): the
 * capture still observes every transport chunk - including the dropped rounds' {@code tool_calls}
 * finish reasons - while only terminal-round text is emitted downstream.
 */
public final class TerminalRoundStreamAdvisor implements StreamAdvisor {

    @Override
    public String getName() {
        return getClass().getSimpleName();
    }

    /**
     * One step before {@link FinishReasonAdvisor} ({@code LOWEST_PRECEDENCE - 1}): the lower order
     * puts this filter outside the capture advisor, which keeps seeing the real chunk stream while
     * downstream consumers only see terminal-round text.
     */
    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE - 2;
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        List<ChatClientResponse> pending = new ArrayList<>();
        Flux<ChatClientResponse> held =
                chain.nextStream(request)
                        .concatMap(
                                chunk -> {
                                    pending.add(chunk);
                                    FinishReason reason = roundReason(chunk.chatResponse());
                                    if (reason == null) {
                                        return Flux.empty();
                                    }
                                    List<ChatClientResponse> round = List.copyOf(pending);
                                    pending.clear();
                                    // tool_calls / malformed_tool_call end a NON-terminal round:
                                    // the loop continues with another model call, so this round's
                                    // narration is dropped. Anything else closes the turn - emit.
                                    return reason == FinishReason.TOOL_CALLS
                                                    || reason == FinishReason.MALFORMED_TOOL_CALL
                                            ? Flux.empty()
                                            : Flux.fromIterable(round);
                                });
        // A stream that completes while a round is still buffered (the provider closed without a
        // finish reason) still emits it - that round was terminal by definition.
        return held.concatWith(Flux.defer(() -> Flux.fromIterable(pending)));
    }

    /** The finish reason a chunk carries, or null: rounds end on a chunk that reports one. */
    private static FinishReason roundReason(ChatResponse response) {
        if (response == null || response.getResults() == null) {
            return null;
        }
        for (Generation generation : response.getResults()) {
            ChatGenerationMetadata metadata = generation == null ? null : generation.getMetadata();
            String raw = metadata == null ? null : metadata.getFinishReason();
            if (raw != null && !raw.isBlank()) {
                return FinishReason.of(raw);
            }
        }
        return null;
    }
}
