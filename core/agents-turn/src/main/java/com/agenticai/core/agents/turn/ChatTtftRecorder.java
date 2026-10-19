package com.agenticai.core.agents.turn;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Flux;

/**
 * Server-side time-to-first-token timer {@code agentic.chat.ttft}, tagged {@code mode} =
 * {@code cache_hit | llm | buffered} (a fixed enum, so cardinality is bounded).
 *
 * <p>The clock starts at chat-turn entry (the caller captures {@link System#nanoTime()}) and
 * stops at the first NON-EMPTY chunk delivered to the subscriber. The buffered-depth SSE
 * keepalive is an empty chunk, so it never counts as the first token. Recorded at most once
 * per subscription; a stream that errors or is cancelled before any token records nothing.
 *
 * <p>Histogram buckets are enabled centrally by the opt-in {@code latency-histograms} profile,
 * so no SLOs are set here. With no registry (slim test fixtures) every call is a pass-through.
 */
final class ChatTtftRecorder {

    static final String METRIC = "agentic.chat.ttft";

    enum Mode {
        CACHE_HIT("cache_hit"), LLM("llm"), BUFFERED("buffered");

        final String tag;

        Mode(String tag) {
            this.tag = tag;
        }
    }

    /** Pass-through used until a registry is injected. */
    static final ChatTtftRecorder NOOP = new ChatTtftRecorder(null);

    private final Timer[] timers = new Timer[Mode.values().length];
    private final boolean enabled;

    ChatTtftRecorder(MeterRegistry registry) {
        this.enabled = registry != null;
        if (enabled) {
            for (Mode m : Mode.values()) {
                timers[m.ordinal()] = Timer.builder(METRIC)
                    .description("Time from chat turn entry to the first emitted token")
                    .tag("mode", m.tag)
                    .register(registry);
            }
        }
    }

    /** Registry-backed recorder, or {@link #NOOP} when no {@code MeterRegistry} bean exists. */
    static ChatTtftRecorder of(ObjectProvider<MeterRegistry> provider) {
        MeterRegistry registry = provider == null ? null : provider.getIfAvailable();
        return registry == null ? NOOP : new ChatTtftRecorder(registry);
    }

    /** Wraps {@code stream} so the first non-empty chunk records the elapsed time since {@code startNanos}. */
    Flux<String> record(Flux<String> stream, Mode mode, long startNanos) {
        if (!enabled) {
            return stream;
        }
        Timer timer = timers[mode.ordinal()];
        return Flux.defer(() -> {
            AtomicBoolean recorded = new AtomicBoolean();
            return stream.doOnNext(chunk -> {
                if (chunk != null && !chunk.isEmpty() && recorded.compareAndSet(false, true)) {
                    timer.record(Duration.ofNanos(System.nanoTime() - startNanos));
                }
            });
        });
    }
}
