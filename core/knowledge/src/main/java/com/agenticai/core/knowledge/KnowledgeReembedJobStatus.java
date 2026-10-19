package com.agenticai.core.knowledge;

import java.util.EnumSet;
import java.util.Set;

/**
 * {@link KnowledgeReembedJob}'s lifecycle (migration V362).
 *
 * <pre>
 *   QUEUED --> EMBEDDING --> SWEEPING --> COMPLETED
 *      \           \            \
 *       +-----------+------------+--> FAILED
 * </pre>
 *
 * <ul>
 *   <li>{@code QUEUED} - accepted, collection marked REEMBEDDING, no work yet.</li>
 *   <li>{@code EMBEDDING} - writing new-model vectors into the shadow table
 *       (V363) in batches; resumable (re-entered from EMBEDDING after a crash).</li>
 *   <li>{@code SWEEPING} - the atomic flip has committed (collection now on the
 *       target model); re-embedding in place any chunk ingested with the old
 *       model while the job ran. Also resumable.</li>
 *   <li>{@code COMPLETED} / {@code FAILED} - terminal. A FAILED job leaves the
 *       collection READY on its OLD model (if it failed before the flip) with
 *       shadow rows kept, so a retry reuses them.</li>
 * </ul>
 */
public enum KnowledgeReembedJobStatus {
    QUEUED,
    EMBEDDING,
    SWEEPING,
    COMPLETED,
    FAILED;

    /** Mirrors the partial unique index predicate in V362 - keep the two in step. */
    public static final Set<KnowledgeReembedJobStatus> ACTIVE = EnumSet.of(QUEUED, EMBEDDING, SWEEPING);

    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED;
    }

    public boolean canTransitionTo(KnowledgeReembedJobStatus next) {
        return switch (this) {
            case QUEUED -> next == EMBEDDING || next == FAILED;
            // EMBEDDING -> EMBEDDING: resuming after a restart is a legal re-entry.
            case EMBEDDING -> next == EMBEDDING || next == SWEEPING || next == FAILED;
            case SWEEPING -> next == SWEEPING || next == COMPLETED || next == FAILED;
            case COMPLETED, FAILED -> false;
        };
    }
}
