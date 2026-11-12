package com.agenticai.core.agents.memorystore;

import com.agenticai.core.agents.memory.spi.MemoryItemView;
import com.agenticai.core.agents.memory.spi.MemoryKind;
import com.agenticai.core.agents.memory.spi.MemoryProvenanceRecord;
import com.agenticai.core.agents.memory.spi.MemoryRecallRequest;
import com.agenticai.core.agents.memory.spi.MemoryRecallResult;
import com.agenticai.core.agents.memory.spi.MemoryRecallSource;
import com.agenticai.core.agents.memory.spi.MemoryTrustLevel;
import com.agenticai.core.security.TenantContext;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;

/**
 * The read side of the unified store: EPISODIC, PROCEDURAL and SELF_MODEL items from {@code
 * memory_item} as a {@link MemoryRecallSource}. Only ACTIVE + TRUSTED, unexpired rows are returned
 * (the query repeats the predicate; the view type rejects anything else again), scoped to the
 * calling tenant and the agent. EPISODIC is also scoped to the end-user subject: without a subject,
 * or for a row about other subjects, nothing is returned. PROCEDURAL and SELF_MODEL are agent-wide,
 * as ADR-0028 section 8 says.
 *
 * <p>Switched off (platform flag, a lower level, or a kind the platform does not allow) it returns
 * an empty result; errors never propagate to the turn. Nothing in the chat prompt calls it yet (WP4
 * wires the composer).
 */
public class MemoryItemStore implements MemoryRecallSource {

    private static final Logger log = LoggerFactory.getLogger(MemoryItemStore.class);

    static final Set<MemoryKind> KINDS =
            EnumSet.of(MemoryKind.EPISODIC, MemoryKind.PROCEDURAL, MemoryKind.SELF_MODEL);
    static final int DEFAULT_TOP_K = 5;

    private final MemoryUnifiedProperties properties;
    private final MemoryUnifiedGate gate;
    private final MemoryItemStorage storage;
    private final ObjectProvider<MemoryEmbedder> embedder;
    private final Clock clock;

    public MemoryItemStore(
            MemoryUnifiedProperties properties,
            MemoryUnifiedGate gate,
            MemoryItemStorage storage,
            ObjectProvider<MemoryEmbedder> embedder,
            Clock clock) {
        this.properties = properties;
        this.gate = gate;
        this.storage = storage;
        this.embedder = embedder;
        this.clock = clock;
    }

    @Override
    public String name() {
        return "memory-items";
    }

    @Override
    public Set<MemoryKind> kinds() {
        return KINDS;
    }

    @Override
    public MemoryRecallResult recall(MemoryRecallRequest request) {
        if (request == null || request.agentId() == null || !gate.enabled(request.agentId())) {
            return MemoryRecallResult.empty("disabled");
        }
        // Fail closed: no tenant, no query, nothing to recall with.
        if (TenantContext.current()
                .map(TenantContext::getTenantId)
                .filter(t -> !t.isBlank())
                .isEmpty()) {
            return MemoryRecallResult.empty("no-tenant");
        }
        Set<MemoryKind> wanted = EnumSet.noneOf(MemoryKind.class);
        for (MemoryKind kind : KINDS) {
            if (request.wants(kind) && properties.getKinds().contains(kind)) {
                wanted.add(kind);
            }
        }
        if (wanted.isEmpty() || request.queryText() == null || request.queryText().isBlank()) {
            return MemoryRecallResult.empty(null);
        }
        MemoryEmbedder embed = embedder.getIfAvailable();
        if (embed == null) {
            return MemoryRecallResult.empty("no-embedder");
        }
        int limit = request.maxItems() > 0 ? request.maxItems() : DEFAULT_TOP_K;
        try {
            float[] query = embed.embed(request.queryText());
            List<MemoryItemView> views = new ArrayList<>();
            for (MemoryKind kind : wanted) {
                String subject = null;
                if (kind == MemoryKind.EPISODIC) {
                    if (request.subjectKey() == null || request.subjectKey().isBlank()) {
                        continue; // episodes are per end user; no subject, no episodes
                    }
                    subject = request.subjectKey();
                }
                for (MemoryItemStorage.Recalled r :
                        storage.recall(
                                request.agentId(), kind, subject, query, limit, clock.instant())) {
                    toView(r).ifPresent(views::add);
                }
            }
            views.sort(Comparator.comparingDouble(MemoryItemView::score).reversed());
            return new MemoryRecallResult(
                    views.size() > limit ? views.subList(0, limit) : views, 0, 0, null);
        } catch (RuntimeException e) {
            log.warn("Unified memory recall failed; returning no items", e);
            return MemoryRecallResult.empty("error");
        }
    }

    private static java.util.Optional<MemoryItemView> toView(MemoryItemStorage.Recalled r) {
        MemoryItem item = r.item();
        // Defence in depth: the query already filtered; a row that is not ACTIVE + TRUSTED never
        // becomes a view.
        if (item.status() != MemoryItemStatus.ACTIVE
                || item.trust() != MemoryTrustLevel.TRUSTED
                || item.content().isBlank()) {
            return java.util.Optional.empty();
        }
        double similarity = Math.max(0.0, Math.min(1.0, 1.0 - r.distance()));
        double score = similarity * (0.6 + 0.4 * Math.max(0.0, Math.min(1.0, r.importance())));
        return java.util.Optional.of(
                new MemoryItemView(
                        item.id(),
                        item.kind(),
                        item.content(),
                        score,
                        new MemoryProvenanceRecord(
                                MemoryTrustLevel.TRUSTED,
                                item.sourceTaint(),
                                item.writer().name())));
    }
}
