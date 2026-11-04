package com.agenticai.core.agents.memorystore.decay;

import com.agenticai.core.agents.memory.spi.MemoryTrustLevel;
import java.util.List;
import java.util.UUID;

/**
 * What the planner proposes for one subject on one night. Deliberately incapable of raising
 * privilege: there is no status or "approve" field anywhere in this type, the only trust it carries
 * is the meet (lowest) of a derived row's inputs, and every operation only removes or derives.
 *
 * @param dryRun the first {@code dry-run-days} after enabling: file proposals only, apply nothing
 * @param breakerTripped the 30% per-subject-per-night circuit breaker cut the plan short; the
 *     caller must file the plan as proposals for a person instead of applying it
 * @param removalCap the most rows this plan may remove (floor of the fraction times total rows)
 * @param deferred operations the caps dropped; they are re-planned on a later night
 * @param batches one batch per trust class, TRUSTED then QUARANTINED, never mixed
 */
public record ConsolidationPlan(
        String subjectKey,
        boolean dryRun,
        boolean breakerTripped,
        int totalRows,
        int removalCap,
        int deferred,
        List<Batch> batches) {

    public ConsolidationPlan {
        batches = List.copyOf(batches);
    }

    /** True when the caller may apply the plan unattended. Otherwise it is filed as proposals. */
    public boolean mayAutoApply() {
        return !dryRun && !breakerTripped;
    }

    public int removalCount() {
        return batches.stream().mapToInt(Batch::removalCount).sum();
    }

    public boolean isEmpty() {
        return batches.stream().allMatch(Batch::isEmpty);
    }

    /**
     * Operations whose removed or summarised rows all have {@code trustClass}. A merge that drops a
     * QUARANTINED duplicate into a TRUSTED keeper sits in the QUARANTINED batch; the keeper is only
     * named, never changed.
     */
    public record Batch(
            MemoryTrustLevel trustClass, List<Forget> forgets, List<Merge> merges, List<Summarise> summaries) {

        public Batch {
            forgets = List.copyOf(forgets);
            merges = List.copyOf(merges);
            summaries = List.copyOf(summaries);
        }

        public int removalCount() {
            return forgets.size()
                    + merges.stream().mapToInt(m -> m.dropIds().size()).sum()
                    + summaries.stream().mapToInt(s -> s.sourceIds().size()).sum();
        }

        public boolean isEmpty() {
            return forgets.isEmpty() && merges.isEmpty() && summaries.isEmpty();
        }
    }

    public enum ForgetReason {
        DECAY,
        QUARANTINE_TTL
    }

    /** Remove one row (tombstone reason DECAY or QUARANTINE_TTL). */
    public record Forget(UUID id, double score, ForgetReason reason) {}

    /** Tombstone {@code dropIds} as {@code MERGED_INTO:keepId}; the kept row is not rewritten. */
    public record Merge(UUID keepId, List<UUID> dropIds) {
        public Merge {
            dropIds = List.copyOf(dropIds);
        }
    }

    /**
     * Fold {@code sourceIds} into one new SUMMARY row. {@code trust} is the meet of the sources and
     * is the only trust the new row may be written with; the model never sees or sets it.
     */
    public record Summarise(
            List<UUID> sourceIds,
            UUID agentId,
            String kind,
            MemoryTrustLevel trust,
            int generation,
            double importance) {
        public Summarise {
            sourceIds = List.copyOf(sourceIds);
        }
    }
}
