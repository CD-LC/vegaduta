package com.agenticai.core.agents.turn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.agenticai.core.agents.turn.ChatTtftRecorder.Mode;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

class ChatTtftRecorderTest {

    private SimpleMeterRegistry registry;
    private ChatTtftRecorder recorder;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        recorder = new ChatTtftRecorder(registry);
    }

    private long count(String mode) {
        return registry.get(ChatTtftRecorder.METRIC).tag("mode", mode).timer().count();
    }

    @Test
    void cacheHitRecordsOnceForFirstChunk() {
        assertEquals(List.of("cached reply"),
            recorder.record(Flux.just("cached reply"), Mode.CACHE_HIT, System.nanoTime()).collectList().block());
        assertEquals(1, count("cache_hit"));
        assertEquals(0, count("llm"));
        assertEquals(0, count("buffered"));
    }

    @Test
    void llmRecordsOncePerTurnNotPerToken() {
        assertEquals(3, recorder.record(Flux.just("a", "b", "c"), Mode.LLM, System.nanoTime()).collectList().block().size());
        assertEquals(1, count("llm"));
    }

    @Test
    void bufferedIgnoresKeepaliveChunks() {
        Flux<String> src = Flux.just("", "", "final answer");
        assertEquals(List.of("", "", "final answer"),
            recorder.record(src, Mode.BUFFERED, System.nanoTime()).collectList().block());
        assertEquals(1, count("buffered"));
    }

    @Test
    void keepaliveOnlyStreamRecordsNothing() {
        recorder.record(Flux.just("", ""), Mode.BUFFERED, System.nanoTime()).collectList().block();
        assertEquals(0, count("buffered"));
    }

    @Test
    void errorBeforeFirstTokenRecordsNothing() {
        assertThrows(IllegalStateException.class, () ->
            recorder.record(Flux.<String>error(new IllegalStateException("x")), Mode.LLM, System.nanoTime()).blockLast());
        assertEquals(0, count("llm"));
    }

    @Test
    void cancelBeforeFirstTokenRecordsNothing() {
        recorder.record(Flux.<String>never(), Mode.LLM, System.nanoTime()).subscribe().dispose();
        assertEquals(0, count("llm"));
    }

    @Test
    void resubscriptionRecordsAgainButNeverTwiceInOneSubscription() {
        Flux<String> f = recorder.record(Flux.just("x", "y"), Mode.LLM, System.nanoTime());
        f.blockLast();
        f.blockLast();
        assertEquals(2, count("llm"));
    }

    @Test
    void noopRecorderIsPassThrough() {
        assertEquals(List.of("t"), ChatTtftRecorder.NOOP.record(Flux.just("t"), Mode.LLM, 0L).collectList().block());
    }
}
