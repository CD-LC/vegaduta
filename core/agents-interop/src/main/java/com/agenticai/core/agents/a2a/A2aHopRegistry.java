package com.agenticai.core.agents.a2a;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Hands the inbound call context of an A2A turn to the outbound {@code a2a_call_agent} tool of the
 * SAME turn, without a ThreadLocal (AGENTS.md rule 12: a tool call may run on any thread).
 *
 * <p>The tool resolver builds the tool for a turn knowing only the chat session id, which for an
 * A2A turn is the request's {@code contextId}. {@link A2aTaskService} therefore registers the
 * context under that id before the turn starts and releases it when the task ends; the tool looks
 * it up by the session id it was built with, at call time. Entries are reference-counted (two
 * concurrent requests on one context share an entry and the HIGHER hop wins, so a second request
 * can only make the limit stricter, never looser) and also expire, so a task that never completes
 * cannot pin memory.
 *
 * <p>Bounded: when {@link #MAX_ENTRIES} live entries exist, {@link #register} refuses (the inbound
 * request then fails) rather than letting a turn run without its hop number, which would let a loop
 * reset its counter.
 */
@Component
public class A2aHopRegistry {

    static final int MAX_ENTRIES = 10_000;
    static final Duration ENTRY_TTL = Duration.ofHours(1);

    private record Entry(A2aCallContext context, int refs, long expiresAtMillis) {}

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private final Clock clock;

    @Autowired
    public A2aHopRegistry() {
        this(Clock.systemUTC());
    }

    A2aHopRegistry(Clock clock) {
        this.clock = clock;
    }

    /**
     * Registers {@code context} for {@code sessionId}.
     *
     * @return false when the registry is full (the caller must refuse the request)
     */
    public boolean register(String sessionId, A2aCallContext context) {
        if (sessionId == null || context == null) {
            return true;
        }
        long now = clock.millis();
        if (entries.size() >= MAX_ENTRIES) {
            entries.values().removeIf(e -> e.expiresAtMillis() <= now);
            if (entries.size() >= MAX_ENTRIES && !entries.containsKey(sessionId)) {
                return false;
            }
        }
        long expires = now + ENTRY_TTL.toMillis();
        entries.merge(
                sessionId,
                new Entry(context, 1, expires),
                (old, fresh) -> {
                    boolean live = old.expiresAtMillis() > now;
                    if (!live) {
                        return fresh;
                    }
                    A2aCallContext keep =
                            fresh.context().hop() > old.context().hop()
                                    ? fresh.context()
                                    : old.context();
                    return new Entry(keep, old.refs() + 1, expires);
                });
        return true;
    }

    /** The context registered for {@code sessionId}, if any and not expired. */
    public Optional<A2aCallContext> lookup(String sessionId) {
        if (sessionId == null) {
            return Optional.empty();
        }
        Entry entry = entries.get(sessionId);
        if (entry == null || entry.expiresAtMillis() <= clock.millis()) {
            return Optional.empty();
        }
        return Optional.of(entry.context());
    }

    /** Drops one registration; the entry goes when the last one is released. */
    public void release(String sessionId) {
        if (sessionId == null) {
            return;
        }
        entries.computeIfPresent(
                sessionId,
                (key, entry) ->
                        entry.refs() <= 1
                                ? null
                                : new Entry(entry.context(), entry.refs() - 1, entry.expiresAtMillis()));
    }

    int size() {
        return entries.size();
    }
}
