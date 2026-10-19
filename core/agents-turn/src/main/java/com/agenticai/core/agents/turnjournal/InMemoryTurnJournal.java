package com.agenticai.core.agents.turnjournal;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Process-local journal for tests, replay and local development. */
public class InMemoryTurnJournal implements TurnJournal {

    private final Map<UUID, List<TurnEvent>> byTurn = new ConcurrentHashMap<>();

    @Override
    public TurnEvent append(
            UUID agentId, UUID turnId, TurnEventType type, String payloadHash, String summary) {
        List<TurnEvent> events = byTurn.computeIfAbsent(turnId, k -> new ArrayList<>());
        synchronized (events) {
            TurnEvent previous = events.isEmpty() ? null : events.get(events.size() - 1);
            TurnEvent event =
                    TurnHashChain.next(previous, agentId, turnId, type, payloadHash, summary);
            events.add(event);
            return event;
        }
    }

    @Override
    public List<TurnEvent> read(UUID agentId, UUID turnId) {
        List<TurnEvent> events = byTurn.get(turnId);
        if (events == null) {
            return List.of();
        }
        synchronized (events) {
            if (events.isEmpty() || !events.get(0).agentId().equals(agentId)) {
                return List.of();
            }
            return List.copyOf(events);
        }
    }
}
