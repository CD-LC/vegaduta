package com.agenticai.core.agents.memorystore;

import com.agenticai.core.agents.memory.spi.MemoryKind;
import com.agenticai.core.agents.memory.spi.MemoryTrustLevel;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * A map-backed {@link MemoryItemStorage} for service tests. Every row passes through the {@link
 * MemoryItem} constructor, which repeats the V681 CHECK constraints, so a service that would
 * violate one fails here exactly as it would in Postgres. The real constraints are exercised in
 * {@code MemoryItemSchemaIT}.
 */
class InMemoryMemoryItemStorage implements MemoryItemStorage {

    final Map<UUID, MemoryItem> items = new LinkedHashMap<>();
    final Map<UUID, float[]> embeddings = new LinkedHashMap<>();
    final Map<UUID, List<MemoryItemSource>> lineage = new LinkedHashMap<>();
    final List<MemoryEvent> events = new ArrayList<>();

    @Override
    public void insertPending(
            MemoryItem item,
            float[] embedding,
            List<MemoryItemSource> sources,
            MemoryEvent created) {
        if (item.status() != MemoryItemStatus.PENDING
                || item.trust() != MemoryTrustLevel.QUARANTINED) {
            throw new IllegalArgumentException("only PENDING + QUARANTINED rows can be inserted");
        }
        items.put(item.id(), item);
        embeddings.put(item.id(), embedding);
        lineage.put(item.id(), List.copyOf(sources));
        events.add(created);
    }

    @Override
    public Optional<MemoryItem> find(UUID agentId, UUID itemId) {
        return Optional.ofNullable(items.get(itemId)).filter(i -> i.agentId().equals(agentId));
    }

    @Override
    public List<MemoryItem> listByStatus(UUID agentId, MemoryItemStatus status, int limit) {
        return items.values().stream()
                .filter(i -> i.agentId().equals(agentId) && i.status() == status)
                .limit(limit)
                .collect(Collectors.toList());
    }

    @Override
    public List<MemoryItem> listByAgent(
            UUID agentId, MemoryItemStatus status, MemoryKind kind, int limit) {
        return items.values().stream()
                .filter(
                        i ->
                                i.agentId().equals(agentId)
                                        && (status == null || i.status() == status)
                                        && (kind == null || i.kind() == kind))
                .sorted(Comparator.comparing(MemoryItem::createdAt).reversed())
                .limit(limit)
                .collect(Collectors.toList());
    }

    @Override
    public List<MemoryItem> listForSubject(String subjectKey, int limit) {
        return items.values().stream()
                .filter(i -> i.subjectKeys().contains(subjectKey))
                .sorted(Comparator.comparing(MemoryItem::createdAt).reversed())
                .limit(limit)
                .collect(Collectors.toList());
    }

    @Override
    public List<MemoryItemSource> lineage(UUID itemId) {
        return lineage.getOrDefault(itemId, List.of());
    }

    @Override
    public List<MemoryEvent> events(UUID itemId) {
        return events.stream().filter(e -> itemId.equals(e.itemId())).collect(Collectors.toList());
    }

    @Override
    public int countByStatus(UUID agentId, MemoryItemStatus status) {
        return (int)
                items.values().stream()
                        .filter(i -> i.agentId().equals(agentId) && i.status() == status)
                        .count();
    }

    @Override
    public int countLive(UUID agentId) {
        return (int)
                items.values().stream()
                        .filter(
                                i ->
                                        i.agentId().equals(agentId)
                                                && (i.status() == MemoryItemStatus.PENDING
                                                        || i.status() == MemoryItemStatus.ACTIVE))
                        .count();
    }

    @Override
    public int countCreatedSince(UUID agentId, Instant since) {
        return (int)
                events.stream()
                        .filter(
                                e ->
                                        e.agentId().equals(agentId)
                                                && e.event() == MemoryEvent.Type.CREATED
                                                && !e.at().isBefore(since))
                        .count();
    }

    @Override
    public boolean activate(
            UUID agentId,
            UUID itemId,
            String approvedBy,
            Instant at,
            MemoryEvent approved,
            UUID supersedes,
            MemoryEvent predecessorEvent) {
        MemoryItem item = items.get(itemId);
        if (item == null
                || !item.agentId().equals(agentId)
                || item.status() != MemoryItemStatus.PENDING
                || item.trust() != MemoryTrustLevel.QUARANTINED) {
            return false;
        }
        items.put(itemId, item.withApproval(approvedBy, at));
        events.add(approved);
        MemoryItem old = supersedes == null ? null : items.get(supersedes);
        if (old != null && old.status() == MemoryItemStatus.ACTIVE) {
            items.put(supersedes, withStatus(old, MemoryItemStatus.SUPERSEDED));
            if (predecessorEvent != null) {
                events.add(predecessorEvent);
            }
        }
        return true;
    }

    @Override
    public boolean transition(
            UUID agentId,
            UUID itemId,
            Set<MemoryItemStatus> from,
            MemoryItemStatus to,
            MemoryEvent event) {
        if (to == MemoryItemStatus.ACTIVE) {
            throw new IllegalArgumentException("ACTIVE is reachable only through activate()");
        }
        MemoryItem item = items.get(itemId);
        if (item == null || !item.agentId().equals(agentId) || !from.contains(item.status())) {
            return false;
        }
        items.put(itemId, withStatus(item, to));
        events.add(event);
        return true;
    }

    @Override
    public boolean setPinned(UUID agentId, UUID itemId, boolean pinned, MemoryEvent event) {
        MemoryItem item = items.get(itemId);
        if (item == null || !item.agentId().equals(agentId) || item.pinned() == pinned) {
            return false;
        }
        items.put(itemId, withPinned(item, pinned));
        events.add(event);
        return true;
    }

    @Override
    public boolean delete(UUID agentId, UUID itemId, MemoryEvent erased) {
        MemoryItem item = items.get(itemId);
        if (item == null || !item.agentId().equals(agentId)) {
            return false;
        }
        items.remove(itemId);
        embeddings.remove(itemId);
        lineage.remove(itemId);
        events.add(erased);
        return true;
    }

    @Override
    public List<UUID> eraseTree(UUID agentId, UUID itemId, MemoryActor actor, Instant at) {
        MemoryItem root = items.get(itemId);
        if (root == null || !root.agentId().equals(agentId)) {
            return List.of();
        }
        // Descendants: items whose lineage names an already-collected id as its memory_item source.
        Map<UUID, MemoryItem> targets = new LinkedHashMap<>();
        targets.put(itemId, root);
        boolean grew = true;
        while (grew) {
            grew = false;
            for (MemoryItem candidate : items.values()) {
                if (targets.containsKey(candidate.id()) || !candidate.agentId().equals(agentId)) {
                    continue;
                }
                boolean derived =
                        lineage.getOrDefault(candidate.id(), List.of()).stream()
                                .anyMatch(
                                        s ->
                                                "memory_item".equals(s.sourceTable())
                                                        && targets.keySet().stream()
                                                                .map(UUID::toString)
                                                                .anyMatch(s.sourceKey()::equals));
                if (derived) {
                    targets.put(candidate.id(), candidate);
                    grew = true;
                }
            }
        }
        events.removeIf(e -> targets.containsKey(e.itemId()));
        for (Map.Entry<UUID, MemoryItem> t : targets.entrySet()) {
            items.remove(t.getKey());
            embeddings.remove(t.getKey());
            lineage.remove(t.getKey());
            events.add(
                    MemoryEvent.of(
                            t.getKey(),
                            agentId,
                            MemoryEvent.Type.ERASED,
                            actor,
                            t.getValue().status(),
                            null,
                            t.getKey().equals(itemId) ? "ERASED" : "ERASED_DERIVED",
                            at));
        }
        return List.copyOf(targets.keySet());
    }

    @Override
    public void appendEvent(MemoryEvent event) {
        events.add(event);
    }

    @Override
    public List<Recalled> recall(
            UUID agentId,
            MemoryKind kind,
            String subjectKey,
            float[] embedding,
            int limit,
            Instant now) {
        return items.values().stream()
                .filter(
                        i ->
                                i.agentId().equals(agentId)
                                        && i.kind() == kind
                                        && i.status() == MemoryItemStatus.ACTIVE
                                        && i.trust() == MemoryTrustLevel.TRUSTED
                                        && !i.expired(now)
                                        && (subjectKey == null
                                                || i.subjectKeys().contains(subjectKey)))
                .map(i -> new Recalled(i, 1.0 - cosine(embedding, embeddings.get(i.id())), 0.0))
                .sorted(Comparator.comparingDouble(Recalled::distance))
                .limit(limit)
                .collect(Collectors.toList());
    }

    /** Test helper: a row as the database would hold it after a direct (non-service) change. */
    void put(MemoryItem item, float[] embedding) {
        items.put(item.id(), item);
        embeddings.put(item.id(), embedding);
    }

    private static MemoryItem withStatus(MemoryItem i, MemoryItemStatus status) {
        return new MemoryItem(
                i.id(),
                i.agentId(),
                i.kind(),
                i.content(),
                i.trust(),
                status,
                i.writer(),
                i.sourceType(),
                i.sourceRef(),
                i.turnId(),
                i.sourceTaint(),
                i.modelGenerated(),
                i.generation(),
                i.subjectKeys(),
                i.version(),
                i.supersedesId(),
                i.createdAt(),
                i.expiresAt(),
                i.approvedBy(),
                i.approvedAt(),
                i.pinned());
    }

    private static MemoryItem withPinned(MemoryItem i, boolean pinned) {
        return new MemoryItem(
                i.id(),
                i.agentId(),
                i.kind(),
                i.content(),
                i.trust(),
                i.status(),
                i.writer(),
                i.sourceType(),
                i.sourceRef(),
                i.turnId(),
                i.sourceTaint(),
                i.modelGenerated(),
                i.generation(),
                i.subjectKeys(),
                i.version(),
                i.supersedesId(),
                i.createdAt(),
                i.expiresAt(),
                i.approvedBy(),
                i.approvedAt(),
                pinned);
    }

    private static double cosine(float[] a, float[] b) {
        double dot = 0;
        double na = 0;
        double nb = 0;
        for (int k = 0; k < a.length; k++) {
            dot += a[k] * b[k];
            na += a[k] * a[k];
            nb += b[k] * b[k];
        }
        return na == 0 || nb == 0 ? 0 : dot / Math.sqrt(na * nb);
    }
}
