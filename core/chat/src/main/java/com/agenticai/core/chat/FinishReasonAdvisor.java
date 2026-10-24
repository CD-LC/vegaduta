package com.agenticai.core.chat;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.core.Ordered;
import reactor.core.publisher.Flux;

/**
 * Reads the provider's finish reason (Anthropic {@code stop_reason}, OpenAI {@code finish_reason},
 * Gemini {@code finishReason}, Ollama {@code done_reason}) off the response metadata and hands it to
 * a {@link FinishReasonCapture}, for the app code that otherwise only sees {@code .content()}. It
 * changes nothing: the response, or each streamed chunk, passes through untouched. It runs last in
 * the chain, next to the model, so it sees what the transport produced.
 */
public final class FinishReasonAdvisor implements CallAdvisor, StreamAdvisor {

    private final FinishReasonCapture capture;

    public FinishReasonAdvisor(FinishReasonCapture capture) {
        this.capture = capture;
    }

    @Override
    public String getName() {
        return getClass().getSimpleName();
    }

    /**
     * One step in front of the terminal model advisor (which sits at {@code LOWEST_PRECEDENCE}):
     * with the same order the chain's tie-break is unspecified and this advisor could end up behind
     * the model call, where it would never run.
     */
    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE - 1;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        capture.begin();
        ChatClientResponse response = chain.nextCall(request);
        capture.observe(response.chatResponse());
        return response;
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        // begin() on subscription, not on assembly: a retried or resubscribed stream starts clean.
        return Flux.defer(() -> {
            capture.begin();
            return chain.nextStream(request).doOnNext(response -> capture.observe(response.chatResponse()));
        });
    }
}
