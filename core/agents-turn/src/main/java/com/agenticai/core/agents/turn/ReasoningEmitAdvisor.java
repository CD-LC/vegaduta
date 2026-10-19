package com.agenticai.core.agents.turn;

import com.agenticai.core.agents.tools.AgentStepEventEmitter;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.core.Ordered;
import reactor.core.publisher.Flux;

/**
 * Forwards the model's own reasoning text into the turn's step-event stream so the console can
 * show live thinking instead of only tool calls. Providers surface reasoning as per-generation
 * metadata ({@code reasoningContent} - the DeepSeek/OpenAI-compat convention Spring AI maps;
 * Ollama's {@code reasoning} field reaches the same key through the wire-alias filter in
 * {@code OllamaAgentChatClientFactory}). Each non-empty chunk is emitted once, the stream passes
 * through byte-identical, and a turn that never asked for step events carries the shared no-op
 * emitter - nothing here ever touches the wire format for callers that opted out.
 */
public final class ReasoningEmitAdvisor implements CallAdvisor, StreamAdvisor {

    /** The metadata key OpenAiChatModel writes (see its generation-metadata block). */
    private static final String REASONING_KEY = "reasoningContent";

    private final AgentStepEventEmitter stepEvents;

    public ReasoningEmitAdvisor(AgentStepEventEmitter stepEvents) {
        this.stepEvents = stepEvents;
    }

    @Override
    public String getName() {
        return getClass().getSimpleName();
    }

    // Run at the very end of the chain: reasoning is observed from the raw model chunks, so the
    // advisor must see whatever the transport produced, after metrics/logging have had their pass.
    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    @Override
    public ChatClientResponse adviseCall(
            ChatClientRequest chatClientRequest, CallAdvisorChain callAdvisorChain) {
        ChatClientResponse response = callAdvisorChain.nextCall(chatClientRequest);
        emit(response.chatResponse());
        return response;
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(
            ChatClientRequest chatClientRequest, StreamAdvisorChain streamAdvisorChain) {
        return streamAdvisorChain
                .nextStream(chatClientRequest)
                .doOnNext(response -> emit(response.chatResponse()));
    }

    private void emit(ChatResponse response) {
        if (response == null || !stepEvents.isEnabled()) {
            return;
        }
        for (var generation : response.getResults()) {
            ChatGenerationMetadata metadata = generation.getMetadata();
            if (metadata == null || !metadata.containsKey(REASONING_KEY)) {
                continue;
            }
            Object reasoning = metadata.get(REASONING_KEY);
            if (reasoning instanceof String text && !text.isBlank()) {
                stepEvents.thinking(text);
            }
        }
    }
}
