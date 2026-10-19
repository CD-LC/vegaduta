package com.agenticai.core.agents.loadtest;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

/**
 * A real (non-mock-framework) {@link ChatModel} implementation that makes
 * NO network call, ever, and needs NO API key configured - the fake model
 * behind {@code AgentChatService}'s load-test seam (see {@link
 * LoadTestTenantAllowlist} for the tenant gate that decides whether this
 * class is ever reached). Every response is a canned, deterministic
 * template built purely from the incoming {@link Prompt} and the current
 * clock - never the real model's reasoning, and deliberately shaped so it
 * reads as obviously synthetic in logs/metrics/traces rather than being
 * mistakable for a real completion.
 *
 * <p>Stateless and side-effect-free, so a single instance can be reused
 * freely (see {@link LoadTestChatClientFactory}) - there is nothing here to
 * race across concurrent load-test turns.
 *
 * <p>{@link #stream(Prompt)} deliberately does NOT return the whole
 * response as one chunk: the entire point of the load-test seam is to
 * exercise the REAL SSE chunking/backpressure machinery downstream (the
 * same {@code ChatClient.prompt().stream().content()} path a real provider
 * turn uses), not just skip straight to a fake final answer. It emits a
 * handful of small chunks spaced a few milliseconds apart on Reactor's own
 * scheduler ({@link Flux#delayElements(Duration)} - no {@code
 * Thread.sleep}, so this never blocks a platform thread, virtual or
 * otherwise).
 */
public final class LoadTestChatModel implements ChatModel {

    /**
     * Fixed filler text, deliberately lorem-ipsum-flavored and NOT
     * resembling any real agent answer, so a load-test response is
     * unmistakable in a transcript or trace even without checking the
     * "load-test" markers around it.
     */
    private static final String LOREM =
        "Lorem ipsum load-test dolor sit amet: this response was generated entirely by the "
            + "load-test mock model and never reached a real LLM provider.";

    private static final int STREAM_CHUNK_COUNT = 6;
    private static final Duration STREAM_CHUNK_DELAY = Duration.ofMillis(20);

    @Override
    public ChatResponse call(Prompt prompt) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(cannedResponse(prompt)))));
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        List<String> chunks = splitIntoChunks(cannedResponse(prompt), STREAM_CHUNK_COUNT);
        // delayElements schedules on Reactor's parallel Scheduler by
        // default - reactive, non-blocking, exercises the same
        // chunk-then-emit shape a real streaming provider response has,
        // without ever touching a network socket.
        return Flux.fromIterable(chunks)
            .delayElements(STREAM_CHUNK_DELAY)
            .map(chunk -> new ChatResponse(List.of(new Generation(new AssistantMessage(chunk)))));
    }

    /**
     * Deterministic per-request template: echoes the inbound prompt's
     * character length (so two different-sized turns visibly produce
     * different output, proving this isn't a single hardcoded string) plus
     * the current epoch millis (so two turns against the SAME prompt still
     * differ, useful for de-duplicating load-test traffic in logs) plus the
     * fixed {@link #LOREM} filler.
     */
    private static String cannedResponse(Prompt prompt) {
        String contents = prompt.getContents();
        int length = contents == null ? 0 : contents.length();
        return "[LOAD-TEST MOCK] echo-length=" + length + " ts=" + System.currentTimeMillis() + " :: " + LOREM;
    }

    /**
     * Splits {@code text} into up to {@code parts} roughly-equal, non-empty
     * chunks (fewer if the text is too short to fill every requested part) -
     * simple char-index slicing, not word-aware, since chunk CONTENT is
     * irrelevant here; only the fact that streaming happens in several
     * pieces matters.
     */
    private static List<String> splitIntoChunks(String text, int parts) {
        List<String> chunks = new ArrayList<>(parts);
        int length = text.length();
        int chunkSize = Math.max(1, (length + parts - 1) / parts);
        for (int start = 0; start < length; start += chunkSize) {
            chunks.add(text.substring(start, Math.min(start + chunkSize, length)));
        }
        return chunks;
    }
}
