package com.agenticai.core.guardrails.taint;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Hand-off for content that enters a turn's prompt before the turn's {@link TurnTaint} exists
 * (auto-injected memory recall, for instance). {@code TurnTaint} is keyed by the step counter that
 * tool resolution creates, and prompt assembly runs (concurrently) before that key is known and
 * without access to it, so the assembler records the source here and tool resolution {@linkplain
 * #bind binds} a sink that seeds the real {@code TurnTaint}. Order does not matter: a source
 * recorded before the bind is replayed into the sink, one recorded after goes straight to it. One
 * instance per turn (held by the turn's tool-activity record); thread-safe; sources only grow.
 */
public final class TurnTaintSeeds {

    private final Object lock = new Object();
    private final List<String> sources = new ArrayList<>(2);
    private final List<Consumer<String>> sinks = new ArrayList<>(1);

    /** Records that content from {@code source} entered the prompt of this turn. */
    public void mark(String source) {
        List<Consumer<String>> toNotify;
        synchronized (lock) {
            if (!sources.contains(source)) {
                sources.add(source);
            }
            toNotify = List.copyOf(sinks);
        }
        toNotify.forEach(s -> s.accept(source));
    }

    /** Registers a sink that receives every source, earlier ones first. */
    public void bind(Consumer<String> sink) {
        List<String> earlier;
        synchronized (lock) {
            sinks.add(sink);
            earlier = List.copyOf(sources);
        }
        earlier.forEach(sink);
    }

    /** Sources recorded so far (for tests and diagnostics). */
    public List<String> sources() {
        synchronized (lock) {
            return List.copyOf(sources);
        }
    }
}
