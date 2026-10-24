package com.agenticai.core.agents.turnjournal;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Process-local journal for tests, replay and local development. */
public class InMemoryTurnJournal implements TurnJournal {

    private final Map<UUID, List<TurnEvent>> byTurn = new ConcurrentHashMap<>();
    private final Map<UUID, String> sessionOfTurn = new ConcurrentHashMap<>();

    @Override
    public TurnEvent append(
            UUID agentId, UUID turnId, TurnEventType type, String payloadHash, String summary) {
        return append(agentId, turnId, null, type, payloadHash, summary);
    }

    @Override
    public TurnEvent append(
            UUID agentId,
            UUID turnId,
            String sessionId,
            TurnEventType type,
            String payloadHash,
            String summary) {
        if (sessionId != null) {
            sessionOfTurn.putIfAbsent(turnId, TurnHashChain.sha256(sessionId));
        }
        List<TurnEvent> events = byTurn.computeIfAbsent(turnId, k -> new ArrayList<>());
        synchronized (events) {
            TurnEvent previous = events.isEmpty() ? null : events.get(events.size() - 1);
            TurnEvent event =
                    TurnHashChain.next(previous, agentId, turnId, type, payloadHash, summary);
            events.add(event);
            return event;
        }
    }

    /** Every turn this journal holds, in no particular order (tests and local development). */
    public java.util.Set<UUID> turnIds() {
        return java.util.Set.copyOf(byTurn.keySet());
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

    @Override
    public int purgeOlderThan(java.time.Instant cutoff, int maxTurns) {
        if (cutoff == null || maxTurns <= 0) {
            return 0;
        }
        List<UUID> due =
                byTurn.entrySet().stream()
                        .filter(e -> lastAt(e.getValue()).isBefore(cutoff))
                        .sorted(Comparator.comparing(e -> lastAt(e.getValue())))
                        .limit(maxTurns)
                        .map(Map.Entry::getKey)
                        .toList();
        int removed = 0;
        for (UUID turnId : due) {
            List<TurnEvent> events = byTurn.remove(turnId);
            sessionOfTurn.remove(turnId);
            removed += events == null ? 0 : events.size();
        }
        return removed;
    }

    private static java.time.Instant lastAt(List<TurnEvent> events) {
        synchronized (events) {
            return events.isEmpty() ? java.time.Instant.MIN : events.get(events.size() - 1).createdAt();
        }
    }

    @Override
    public List<TurnSummary> listBySession(UUID agentId, String sessionId, int limit) {
        if (sessionId == null || limit <= 0) {
            return List.of();
        }
        List<TurnSummary> found = new ArrayList<>();
        String wanted = TurnHashChain.sha256(sessionId);
        sessionOfTurn.forEach(
                (turnId, sessionHash) -> {
                    if (!Objects.equals(sessionHash, wanted)) {
                        return;
                    }
                    List<TurnEvent> events = read(agentId, turnId);
                    if (events.isEmpty()) {
                        return;
                    }
                    String commit =
                            events.stream()
                                    .filter(e -> e.type() == TurnEventType.COMMIT)
                                    .reduce((a, b) -> b)
                                    .map(TurnEvent::summary)
                                    .orElse(null);
                    found.add(
                            new TurnSummary(
                                    turnId,
                                    agentId,
                                    events.get(0).createdAt(),
                                    events.get(events.size() - 1).createdAt(),
                                    events.size(),
                                    TurnSummary.statusOf(commit)));
                });
        found.sort(Comparator.comparing(TurnSummary::startedAt).reversed());
        return found.size() > limit ? List.copyOf(found.subList(0, limit)) : List.copyOf(found);
    }
}
