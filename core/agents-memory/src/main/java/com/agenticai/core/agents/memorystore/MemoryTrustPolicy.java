package com.agenticai.core.agents.memorystore;

import com.agenticai.core.agents.memory.spi.MemoryProposalStatus;
import com.agenticai.core.agents.memory.spi.MemoryTrustLevel;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The inheritance rules of ADR-0028 section 4, pure and unit-tested; the V681 CHECK constraints
 * back the same invariants in the database.
 *
 * <ol>
 *   <li>A derived item takes the lowest trust of its sources (the meet), the union of their taint,
 *       and {@code max(generation) + 1}; it is always model-generated.
 *   <li>A model-generated source is refused as distiller input unless a human confirmed it.
 *   <li>A source that was rejected, reverted or superseded is not usable.
 *   <li>A generation past the configured cap is refused, at proposal and again at approval.
 * </ol>
 */
public final class MemoryTrustPolicy {

    private MemoryTrustPolicy() {}

    /** What the policy needs to know about one source row. */
    public record SourceFacts(
            MemoryTrustLevel trust,
            List<String> sourceTaint,
            MemoryItemStatus status,
            boolean modelGenerated,
            boolean humanConfirmed,
            int generation) {

        public SourceFacts {
            trust = trust == null ? MemoryTrustLevel.QUARANTINED : trust;
            sourceTaint = sourceTaint == null ? List.of() : List.copyOf(sourceTaint);
        }

        /** A source outside memory_item (agent_memory, a turn, ...): only its trust and taint are known. */
        public static SourceFacts external(MemoryTrustLevel trust, List<String> taint) {
            return new SourceFacts(trust, taint, null, false, true, 0);
        }

        static SourceFacts of(MemoryItem item) {
            return new SourceFacts(
                    item.trust(),
                    item.sourceTaint(),
                    item.status(),
                    item.modelGenerated(),
                    item.humanConfirmed(),
                    item.generation());
        }
    }

    /** The outcome of {@link #derive}: either the inherited provenance or the reason it was refused. */
    public record Derivation(
            MemoryTrustLevel trust,
            List<String> sourceTaint,
            int generation,
            boolean modelGenerated,
            String refusal) {

        public boolean refused() {
            return refusal != null;
        }

        /** The proposal verdict: PENDING only when nothing was inherited that needs a closer look. */
        public MemoryProposalStatus proposalStatus() {
            return trust == MemoryTrustLevel.TRUSTED && sourceTaint.isEmpty()
                    ? MemoryProposalStatus.PENDING
                    : MemoryProposalStatus.QUARANTINED;
        }
    }

    public static final String MODEL_GENERATED_SOURCE = "MODEL_GENERATED_SOURCE";
    public static final String SOURCE_NOT_USABLE = "SOURCE_NOT_USABLE";
    public static final String GENERATION_CAP = "GENERATION_CAP";

    /**
     * Derives provenance for a new item. No sources means an item built straight from an event: it
     * has generation 0 when a person or a tool wrote it and 1 when a model did, and inherits nothing.
     */
    public static Derivation derive(List<SourceFacts> sources, boolean modelWritten, int maxGeneration) {
        if (sources == null || sources.isEmpty()) {
            int generation = modelWritten ? 1 : 0;
            if (generation > maxGeneration) {
                return refused(GENERATION_CAP);
            }
            return new Derivation(MemoryTrustLevel.TRUSTED, List.of(), generation, modelWritten, null);
        }
        List<MemoryTrustLevel> levels = new ArrayList<>();
        Set<String> taint = new LinkedHashSet<>();
        int maxSourceGeneration = 0;
        for (SourceFacts source : sources) {
            if (source.status() == MemoryItemStatus.REJECTED
                    || source.status() == MemoryItemStatus.REVERTED
                    || source.status() == MemoryItemStatus.SUPERSEDED) {
                return refused(SOURCE_NOT_USABLE);
            }
            if (source.modelGenerated() && !source.humanConfirmed()) {
                return refused(MODEL_GENERATED_SOURCE);
            }
            // A PENDING source is not trusted yet whatever its trust column says.
            levels.add(source.status() == MemoryItemStatus.PENDING ? MemoryTrustLevel.QUARANTINED : source.trust());
            taint.addAll(source.sourceTaint());
            maxSourceGeneration = Math.max(maxSourceGeneration, source.generation());
        }
        int generation = maxSourceGeneration + 1;
        if (generation > maxGeneration || generation > MemoryItem.MAX_GENERATION) {
            return refused(GENERATION_CAP);
        }
        return new Derivation(MemoryTrustLevel.meet(levels), List.copyOf(taint), generation, true, null);
    }

    /**
     * Why {@code item} may not be activated now, or empty when it may. Activation is the human
     * approve: the item must be PENDING, not expired, and within the generation cap.
     */
    public static Optional<String> activationRefusal(MemoryItem item, int maxGeneration, Instant now) {
        if (item.status() != MemoryItemStatus.PENDING) {
            return Optional.of("NOT_PENDING");
        }
        if (item.generation() > maxGeneration) {
            return Optional.of(GENERATION_CAP);
        }
        if (item.expired(now)) {
            return Optional.of("EXPIRED");
        }
        return Optional.empty();
    }

    private static Derivation refused(String reason) {
        return new Derivation(MemoryTrustLevel.QUARANTINED, List.of(), 0, true, reason);
    }
}
