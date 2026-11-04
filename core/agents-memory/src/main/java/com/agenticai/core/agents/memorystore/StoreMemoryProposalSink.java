package com.agenticai.core.agents.memorystore;

import com.agenticai.core.agents.memory.spi.MemoryKind;
import com.agenticai.core.agents.memory.spi.MemoryProposalReceipt;
import com.agenticai.core.agents.memory.spi.MemoryProposalSink;
import com.agenticai.core.agents.memory.spi.MemoryProposalStatus;
import com.agenticai.core.agents.memory.spi.MemorySourceRef;
import com.agenticai.core.agents.memory.spi.MemoryWriteProposal;
import com.agenticai.core.security.TenantContext;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;

/**
 * The store-backed write port. It creates only QUARANTINED + PENDING rows ({@link
 * MemoryItem#pending}); it has no code path to TRUSTED or ACTIVE and holds no reference to the
 * review service, which is the only place an item is promoted.
 *
 * <p>What it decides, in order: the platform and lower-level flags; the kind (SEMANTIC lives
 * elsewhere, and the platform must allow the kind); a tenant on the thread; the content screens;
 * the per-agent bounds (pending queue, items, writes per hour); then lineage: sources that are
 * {@code memory_item} rows are read from the store (a caller cannot claim better trust than the
 * source has), the derivation takes the lowest source trust, the union of taint and
 * {@code max(generation) + 1}, and a model-generated source that no human confirmed, or a
 * generation past {@code max-generation}, refuses the proposal. A refusal appends a REFUSED event
 * (reason code only, never text) and bumps {@code memory_write_refused_total}.
 *
 * <p>The bounds are read-then-insert, not atomic: two concurrent proposals can overshoot a limit by
 * a few. They protect the review queue from flooding, not an invariant.
 */
public class StoreMemoryProposalSink implements MemoryProposalSink {

    private static final Logger log = LoggerFactory.getLogger(StoreMemoryProposalSink.class);

    /** The result of {@link #proposeDetailed}: a receipt, or the reason code of the refusal. */
    public record Outcome(MemoryProposalReceipt receipt, String refusal) {

        public boolean accepted() {
            return receipt != null;
        }
    }

    private final MemoryUnifiedProperties properties;
    private final MemoryUnifiedGate gate;
    private final MemoryItemStorage storage;
    private final ObjectProvider<MemoryEmbedder> embedder;
    private final List<MemoryContentScreen> screens;
    private final ObjectProvider<MeterRegistry> meters;
    private final Clock clock;

    public StoreMemoryProposalSink(
            MemoryUnifiedProperties properties,
            MemoryUnifiedGate gate,
            MemoryItemStorage storage,
            ObjectProvider<MemoryEmbedder> embedder,
            List<MemoryContentScreen> screens,
            ObjectProvider<MeterRegistry> meters,
            Clock clock) {
        this.properties = properties;
        this.gate = gate;
        this.storage = storage;
        this.embedder = embedder;
        this.screens = List.copyOf(screens);
        this.meters = meters;
        this.clock = clock;
    }

    @Override
    public Optional<MemoryProposalReceipt> propose(MemoryWriteProposal proposal) {
        return Optional.ofNullable(proposeDetailed(proposal).receipt());
    }

    public Outcome proposeDetailed(MemoryWriteProposal proposal) {
        if (proposal == null || !gate.enabled(proposal.agentId())) {
            return refused(null, "DISABLED", false);
        }
        if (proposal.kind() == MemoryKind.SEMANTIC) {
            return refused(proposal, "KIND_NOT_STORED", false);
        }
        if (!properties.getKinds().contains(proposal.kind())) {
            return refused(proposal, "KIND_DISABLED", false);
        }
        if (TenantContext.current().map(TenantContext::getTenantId).filter(t -> !t.isBlank()).isEmpty()) {
            return refused(proposal, "NO_TENANT", false);
        }
        try {
            return propose0(proposal);
        } catch (RuntimeException e) {
            log.warn("Memory proposal failed; nothing stored", e);
            return refused(proposal, "ERROR", false);
        }
    }

    private Outcome propose0(MemoryWriteProposal p) {
        for (MemoryContentScreen screen : screens) {
            Optional<String> reason = screen.reject(p.text());
            if (reason.isPresent()) {
                return refused(p, "SCREEN_" + reason.get().replaceAll("[^A-Za-z0-9_]", "_"), true);
            }
        }
        Instant now = clock.instant();
        if (storage.countByStatus(p.agentId(), MemoryItemStatus.PENDING) >= properties.getMaxPendingPerAgent()) {
            return refused(p, "PENDING_QUEUE_FULL", true);
        }
        if (storage.countLive(p.agentId()) >= properties.getMaxItemsPerAgent()) {
            return refused(p, "AGENT_FULL", true);
        }
        if (storage.countCreatedSince(p.agentId(), now.minus(Duration.ofHours(1)))
                >= properties.getMaxWritesPerHourPerAgent()) {
            return refused(p, "RATE_LIMIT", true);
        }

        // Lineage: memory_item sources come from the store, others from the caller (fail closed on trust).
        Map<String, MemoryItemSource> lineage = new LinkedHashMap<>();
        List<MemoryTrustPolicy.SourceFacts> facts = new ArrayList<>();
        Set<String> subjects = new LinkedHashSet<>();
        if (p.subjectKey() != null && !p.subjectKey().isBlank()) {
            subjects.add(p.subjectKey());
        }
        for (MemorySourceRef ref : p.sources()) {
            String key = ref.table() + "/" + ref.key();
            if (lineage.containsKey(key)) {
                continue;
            }
            if (MemorySourceRef.MEMORY_ITEM.equals(ref.table())) {
                Optional<MemoryItem> source = findItem(p.agentId(), ref.key());
                if (source.isEmpty()) {
                    return refused(p, "SOURCE_UNKNOWN", true);
                }
                MemoryItem item = source.get();
                facts.add(MemoryTrustPolicy.SourceFacts.of(item));
                subjects.addAll(item.subjectKeys());
                lineage.put(key, new MemoryItemSource(ref.table(), ref.key(), item.trust()));
            } else {
                facts.add(MemoryTrustPolicy.SourceFacts.external(ref.trust(), ref.sourceTaint()));
                lineage.put(key, new MemoryItemSource(ref.table(), ref.key(), ref.trust()));
            }
        }
        boolean modelWritten = p.modelGenerated() || !p.sources().isEmpty();
        MemoryTrustPolicy.Derivation derivation =
                MemoryTrustPolicy.derive(facts, modelWritten, properties.getMaxGeneration());
        if (derivation.refused()) {
            return refused(p, derivation.refusal(), true);
        }

        // Taint of the proposal itself (the write gate) plus everything inherited.
        Set<String> taint = new LinkedHashSet<>(p.provenance().sourceTaint());
        taint.addAll(derivation.sourceTaint());
        MemoryProposalStatus verdict =
                MemoryProposalPolicy.statusFor(p) == MemoryProposalStatus.QUARANTINED
                                || derivation.proposalStatus() == MemoryProposalStatus.QUARANTINED
                        ? MemoryProposalStatus.QUARANTINED
                        : MemoryProposalStatus.PENDING;

        MemoryEmbedder embed = embedder.getIfAvailable();
        if (embed == null) {
            return refused(p, "NO_EMBEDDER", true);
        }
        float[] embedding = embed.embed(p.text());

        UUID id = UUID.randomUUID();
        boolean derived = !p.sources().isEmpty();
        MemoryItem item =
                MemoryItem.pending(
                        id,
                        p.agentId(),
                        p.kind(),
                        p.text(),
                        derived ? MemoryWriter.DISTILLER : MemoryWriter.AGENT,
                        p.turnId() == null ? "UNKNOWN" : "TURN",
                        p.turnId() == null ? null : p.turnId().toString(),
                        p.turnId() == null ? null : p.turnId().toString(),
                        List.copyOf(taint),
                        derivation.modelGenerated(),
                        derivation.generation(),
                        List.copyOf(subjects),
                        1,
                        null,
                        now,
                        p.kind() == MemoryKind.EPISODIC ? now.plus(Duration.ofDays(properties.getEpisodicTtlDays())) : null);
        MemoryEvent created =
                MemoryEvent.of(
                        id,
                        p.agentId(),
                        MemoryEvent.Type.CREATED,
                        derived ? MemoryActor.system("distiller") : MemoryActor.agent(p.agentId().toString()),
                        null,
                        MemoryItemStatus.PENDING,
                        "KIND=" + p.kind() + ";VERDICT=" + verdict + ";SOURCES=" + lineage.size() + ";GENERATION="
                                + derivation.generation(),
                        now);
        storage.insertPending(item, embedding, List.copyOf(lineage.values()), created);
        return new Outcome(new MemoryProposalReceipt(id, verdict), null);
    }

    private Optional<MemoryItem> findItem(UUID agentId, String key) {
        try {
            return storage.find(agentId, UUID.fromString(key));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private Outcome refused(MemoryWriteProposal p, String reason, boolean record) {
        if (record && p != null) {
            try {
                storage.appendEvent(
                        MemoryEvent.of(
                                null,
                                p.agentId(),
                                MemoryEvent.Type.REFUSED,
                                MemoryActor.agent(p.agentId().toString()),
                                null,
                                null,
                                "KIND=" + p.kind() + ";REASON=" + reason,
                                clock.instant()));
            } catch (RuntimeException e) {
                log.warn("Could not record a refused memory proposal", e);
            }
        }
        if (record) {
            MeterRegistry registry = meters.getIfAvailable();
            if (registry != null) {
                registry.counter("memory_write_refused_total", "reason", reason).increment();
            }
        }
        return new Outcome(null, reason);
    }
}
